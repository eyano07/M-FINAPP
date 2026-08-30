package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.caisse.EcritureResponse;
import com.mbsc.finapp.dto.caisse.LigneBalanceResponse;
import com.mbsc.finapp.dto.caisse.TransactionCaisseRequest;
import com.mbsc.finapp.dto.caisse.TransactionCaisseResponse;
import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.dto.comptabilite.PieceResponse;
import com.mbsc.finapp.exception.ReglePrioriteException;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.TransactionCaisseRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * Module Caisse : enregistrement des operations, paiement des notes de frais
 * transmises et consultation du Grand Livre / de la balance.
 *
 * <p>L'ecriture en partie double est deleguee a
 * {@link EcritureComptableService} (logique mutualisee avec la synchro).</p>
 */
@Service
@RequiredArgsConstructor
public class CaisseService {

    private static final Logger log = LoggerFactory.getLogger(CaisseService.class);

    private final TransactionCaisseRepository transactionRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final NoteFraisRepository noteRepository;
    private final PieceComptableRepository pieceRepository;
    private final EcritureComptableService comptabilite;
    private final ConversionDeviseService conversionDevise;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final IaAssistantService ia;
    private final NotificationService notificationService;
    /** Constate l'ecart de change realise entre engagement et reglement. */
    private final EcartChangeService ecartChange;
    /**
     * Regles de tresorerie partagees avec la banque et le mobile money.
     * Ces regles etaient auparavant dupliquees ici : une correction apportee
     * au service partage ne s'appliquait donc pas au canal caisse, le plus
     * utilise.
     */
    private final RegleTresorerieService regleTresorerie;
    private final StockService stockService;
    /** Échange de consigne des achats de boissons du module Restaurant, déclenché au paiement. */
    private final RestaurantService restaurantService;

    // ---------------------------------------------------------------------
    // Saisie directe
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionCaisseResponse enregistrer(TransactionCaisseRequest req) {
        User caissier = currentUser.requireUser();
        CompteOHADA contrepartie = comptabilite.compteParNumero(req.compteContrepartie());
        regleTresorerie.validerSensContrepartie(req.sens(), contrepartie);

        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .montant(req.montant())
            .sens(req.sens())
            .libelle(req.libelle())
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(conversionDevise.tauxCourant())
            .build();

        TransactionCaisse saved = comptabilite.enregistrer(transaction, contrepartie, req.libelle());
        log.info("Operation de caisse saisie [ref={}, par={}]", saved.getReference(), caissier.getEmail());
        return TransactionCaisseResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Paiement d'une note de frais (TRANSMISE_CAISSE -> PAYEE)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionCaisseResponse payerNote(Long noteId) {
        User caissier = currentUser.requireUser();
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", noteId));

        if (note.getStatut() != StatutNote.TRANSMISE_CAISSE) {
            throw new TransitionInvalideException(
                "Seule une note TRANSMISE_CAISSE peut etre payee (etat actuel : " + note.getStatut() + ")");
        }
        if (note.estDejaReglee()) {
            throw new TransitionInvalideException(
                "Cette note a deja une transaction de tresorerie associee (caisse, banque ou mobile money)");
        }

        // ── Conversion en devise de base (CDF) ───────────────────────────────
        // Le Grand Livre est tenu en CDF : une note en USD est convertie au
        // taux du jour, la devise et le taux étant tracés sur les écritures.
        //
        // Le taux est résolu UNE SEULE FOIS pour toute l'opération, puis
        // réutilisé pour les écritures et pour la transaction. Deux
        // résolutions séparées pourraient encadrer un changement de taux et
        // faire diverger transactions_caisse.taux_journalier de
        // grand_livre.taux_applique pour un même mouvement.
        BigDecimal tauxOperation = conversionDevise.tauxCourant();
        ConversionDeviseService.Conversion conversion =
            conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxOperation);

        // ── Règle de priorité ────────────────────────────────────────────────
        regleTresorerie.validerReglePriorite(note, conversion.montantBase(), "571", "la caisse");

        // ── Libelle explicite via l'agent IA ─────────────────────────────────
        // Le libelle brut ("Paiement note NF-...") est reformule avec le
        // vocabulaire comptable usuel a partir du detail des lignes de
        // depense, pour que le journal de caisse soit directement lisible
        // par un comptable (repli automatique sur un gabarit local si l'IA
        // est indisponible).
        String detailLignes = note.getLignes().stream()
            .map(l -> {
                CompteOHADA c = l.getCompteImputation();
                String libelleCompte = c != null ? c.getNumero() + " " + c.getLibelle() : "charges diverses";
                String desc = l.getDescription();
                return libelleCompte + (desc != null && !desc.isBlank() ? " (" + desc + ")" : "");
            })
            .collect(java.util.stream.Collectors.joining(" ; "));
        String libelleExplicite = ia.reformulerLibellePaiement(note.getReference(), note.getObjet(), detailLignes);

        String libellePaiement = libelleExplicite
            + (conversion.estConvertie()
                ? " (" + conversion.montantOrigine().toPlainString() + " "
                    + conversion.deviseOrigine() + " @ " + conversion.tauxApplique().toPlainString() + ")"
                : "");
        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .noteFrais(note)
            .montant(conversion.montantBase())
            .sens(SensTransaction.DECAISSEMENT)
            .libelle(libellePaiement)
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(tauxOperation)
            .build();

        // ── Ventilation comptable ligne par ligne ────────────────────────────
        // Chaque ligne de depense de la note (compte + montant propres) genere
        // sa propre ecriture de debit ; le credit unique solde la caisse pour
        // le montant total converti.
        RegleTresorerieService.VentilationNote ventilation =
            regleTresorerie.construireLignesDebitDepuisNote(note, conversion);
        List<EcritureComptableService.LigneDebit> lignesDebit = ventilation.lignesDebit();

        TransactionCaisse saved = comptabilite.enregistrerDecaissementMultiLigne(
            transaction, lignesDebit, libellePaiement, conversion);

        // Entree en stock des lignes "achat de marchandise" : rattachee a la
        // piece qui vient d'etre generee, sans nouvelle ecriture (voir
        // StockService.entreesDepuisNoteFraisInterne).
        LocalDate dateReglement = saved.getDateOperation().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (!ventilation.entreesStock().isEmpty()) {
            PieceComptable piece = saved.getEcritures().get(0).getPiece();
            stockService.entreesDepuisNoteFraisInterne(
                dateReglement, libellePaiement, ventilation.entreesStock(), piece, caissier);
        }
        // Achat de boissons (module Restaurant) : l'echange de consigne rend
        // les bouteilles vides equivalentes, au meme moment que l'entree en
        // stock de la boisson elle-meme — voir
        // RestaurantService.enregistrerAchatVidesDepuisNoteFraisInterne.
        for (LigneNoteFrais ligne : note.getLignes()) {
            if (ligne.isAchatMarchandise() && ligne.isEchangeConsigne()
                && ligne.getArticle() != null && ligne.getArticle().getType() == TypeArticle.BOISSON) {
                restaurantService.enregistrerAchatVidesDepuisNoteFraisInterne(
                    ligne.getArticle(), RestaurantService.bouteilles(ligne.getQuantiteMarchandise()),
                    dateReglement, "Échange de consigne — " + note.getReference(), caissier);
            }
        }
        // Ecart de change realise : la note a ete engagee a un taux fige lors
        // de sa transmission, elle est reglee au taux du jour. La difference
        // est reclassee en 676/776 par une piece dediee, au lieu de rester
        // invisible dans le compte de charge.
        ecartChange.comptabiliserEcart(note, tauxOperation,
            saved.getDateOperation().atZone(java.time.ZoneOffset.UTC).toLocalDate(), caissier);

        note.setStatut(StatutNote.PAYEE);
        note.addObservation(com.mbsc.finapp.domain.ObservationNote.builder()
            .noteFrais(note)
            .auteur(caissier)
            .statutAuMoment(StatutNote.PAYEE)
            .commentaire("Paiement execute, recu " + saved.getNumeroRecu())
            .build());

        log.info("Note {} payee par {} [recu={}]",
            note.getReference(), caissier.getEmail(), saved.getNumeroRecu());
        notificationService.notifierUtilisateur(note.getCreateur(),
            com.mbsc.finapp.domain.enums.TypeNotification.NOTE_PAYEE,
            "Note payée", note.getReference() + " — reçu " + saved.getNumeroRecu(),
            "/notes-frais/" + note.getId(), note);
        return TransactionCaisseResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Encaissement direct d'une note de frais (BROUILLON -> PAYEE)
    // ---------------------------------------------------------------------

    /**
     * Execute directement une note d'encaissement : contrairement au
     * décaissement, il n'y a ni soumission au DFIN, ni validation du DA — le
     * caissier émet la recette et l'encaisse dans le même geste (une note
     * peut néanmoins être enregistrée en brouillon d'abord, le temps de
     * composer ses lignes, puis encaissée séparément).
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionCaisseResponse encaisserNote(Long noteId) {
        User caissier = currentUser.requireUser();
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", noteId));

        if (note.getSens() != SensTransaction.ENCAISSEMENT) {
            throw new TransitionInvalideException(
                "Seule une note d'encaissement peut être encaissée directement (cette note est un décaissement)");
        }
        if (note.getStatut() != StatutNote.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une note d'encaissement BROUILLON peut être encaissée (état actuel : " + note.getStatut() + ")");
        }
        if (note.estDejaReglee()) {
            throw new TransitionInvalideException(
                "Cette note a deja une transaction de tresorerie associee (caisse, banque ou mobile money)");
        }

        BigDecimal tauxOperation = conversionDevise.tauxCourant();
        ConversionDeviseService.Conversion conversion =
            conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxOperation);

        String libelleEncaissement = "Encaissement " + note.getReference() + " – " + note.getObjet()
            + (conversion.estConvertie()
                ? " (" + conversion.montantOrigine().toPlainString() + " "
                    + conversion.deviseOrigine() + " @ " + conversion.tauxApplique().toPlainString() + ")"
                : "");

        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .noteFrais(note)
            .montant(conversion.montantBase())
            .sens(SensTransaction.ENCAISSEMENT)
            .libelle(libelleEncaissement)
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(tauxOperation)
            .build();

        // ── Ventilation comptable ligne par ligne ────────────────────────────
        // Chaque ligne de recette de la note (compte + montant propres) genere
        // sa propre ecriture de credit ; le debit unique alimente la caisse
        // pour le montant total converti.
        List<EcritureComptableService.LigneCredit> lignesCredit = construireLignesCredit(note, conversion);

        TransactionCaisse saved = comptabilite.enregistrerEncaissementMultiLigne(
            transaction, lignesCredit, libelleEncaissement, conversion);

        note.setStatut(StatutNote.PAYEE);
        note.addObservation(com.mbsc.finapp.domain.ObservationNote.builder()
            .noteFrais(note)
            .auteur(caissier)
            .statutAuMoment(StatutNote.PAYEE)
            .commentaire("Encaissement execute, recu " + saved.getNumeroRecu())
            .build());

        log.info("Note d'encaissement {} executee par {} [recu={}]",
            note.getReference(), caissier.getEmail(), saved.getNumeroRecu());
        return TransactionCaisseResponse.from(saved);
    }

    /**
     * Construit une ligne de credit par ligne de recette de la note, chacune
     * imputee a son propre compte (ou au compte de produits divers 758 par
     * defaut). Symetrique de {@link #construireLignesDebit}.
     */
    private List<EcritureComptableService.LigneCredit> construireLignesCredit(
            NoteFrais note, ConversionDeviseService.Conversion conversionTotale) {
        List<LigneNoteFrais> lignes = note.getLignes();
        List<EcritureComptableService.LigneCredit> resultat = new ArrayList<>();
        BigDecimal sommeConvertie = BigDecimal.ZERO;
        for (int i = 0; i < lignes.size(); i++) {
            LigneNoteFrais ligne = lignes.get(i);
            CompteOHADA compte = ligne.getCompteImputation() != null
                ? ligne.getCompteImputation()
                : comptabilite.compteProduitsDiversParDefaut();

            BigDecimal montantCDF;
            boolean derniereLigne = (i == lignes.size() - 1);
            if (derniereLigne) {
                montantCDF = conversionTotale.montantBase().subtract(sommeConvertie);
            } else if (conversionTotale.estConvertie()) {
                montantCDF = ligne.getMontant().multiply(conversionTotale.tauxApplique())
                    .setScale(2, RoundingMode.HALF_UP);
            } else {
                montantCDF = ligne.getMontant();
            }
            sommeConvertie = sommeConvertie.add(montantCDF);
            resultat.add(new EcritureComptableService.LigneCredit(compte, montantCDF));
        }
        return resultat;
    }

    // NB : l'ancienne « écriture directe » (une seule jambe dans le Grand
    // Livre, sans pièce ni contrepartie) a été supprimée : elle violait la
    // partie double. Toute saisie manuelle passe désormais par une pièce
    // comptable équilibrée (module Comptabilité) ou une opération de caisse.


    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<TransactionCaisseResponse> listerTransactions() {
        return transactionRepository.findAllByOrderByDateEnregistrementDesc().stream()
            .map(TransactionCaisseResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EcritureResponse> grandLivre() {
        return ecritureRepository.listerAvecDetails().stream()
            .map(EcritureResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<LigneBalanceResponse> balance() {
        return ecritureRepository.calculerBalance().stream()
            .map(row -> LigneBalanceResponse.of(
                (String) row[0],
                (String) row[1],
                (TypeCompte) row[2],
                (BigDecimal) row[3],
                (BigDecimal) row[4]))
            .toList();
    }

    /**
     * Journal de caisse : pièces comptabilisées ayant mouvementé le compte
     * 571 (et sous-comptes) sur la période, en ordre chronologique. Vue
     * restreinte pour le caissier (contrairement au livre-journal complet,
     * réservé à la direction financière, qui expose aussi les autres
     * journaux).
     *
     * <p>Filtré par COMPTE réellement mouvementé, pas par étiquette de
     * journal : une pièce issue d'un import ou saisie manuellement peut
     * mouvementer la caisse sans jamais porter le journal CAISSE — elle
     * doit tout de même apparaître ici.</p>
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public LivreJournalResponse journal(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<PieceResponse> pieces = pieceRepository.livreJournalParCompte(EcritureComptableService.COMPTE_CAISSE, debut, fin)
            .stream()
            .map(PieceResponse::from)
            .toList();

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (PieceResponse p : pieces) {
            totalDebit = totalDebit.add(p.totalDebit() != null ? p.totalDebit() : BigDecimal.ZERO);
            totalCredit = totalCredit.add(p.totalCredit() != null ? p.totalCredit() : BigDecimal.ZERO);
        }
        return new LivreJournalResponse(debut, fin, pieces, totalDebit, totalCredit);
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------


}

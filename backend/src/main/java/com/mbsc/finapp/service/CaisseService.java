package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.ChargeCamionMinerai;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
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
import com.mbsc.finapp.dto.caisse.AchatMarchandiseRequest;
import com.mbsc.finapp.dto.caisse.ReglementCamionsRequest;
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
import org.springframework.util.StringUtils;

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
    /** Camions de minerais : reglement de la dette fournisseur nee a la reception. */
    private final MineraiService mineraiService;

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
    // Achat de marchandise au comptant
    // ---------------------------------------------------------------------

    /**
     * Achat d'une marchandise regle en especes, saisi par le caissier.
     *
     * <p><b>Deux ecritures indissociables, conformes a l'inventaire permanent
     * du SYSCOHADA revise :</b></p>
     * <ol>
     *   <li><b>D 601x Achats de marchandises / C 571 Caisse</b> — l'achat et
     *       la sortie de tresorerie. C'est une transaction de caisse
     *       ordinaire (journal CAISSE), qui apparait donc dans l'historique
     *       des operations comme n'importe quel decaissement.</li>
     *   <li><b>D 311x Marchandises / C 6031 Variations des stocks</b> —
     *       l'entree en stock (journal STOCK), portee par
     *       {@code StockService.enregistrerEntreeAchatInterne}.</li>
     * </ol>
     *
     * <p>Imputer directement le compte de stock au decaissement (D 311 /
     * C 571) aurait produit un bilan juste mais un compte de resultat faux :
     * ni l'achat (601) ni sa variation de stock (6031) n'apparaitraient, or
     * c'est precisement leur difference qui forme le « cout d'achat des
     * marchandises vendues » du compte de resultat SYSCOHADA. Le couple
     * 601/6031 est donc obligatoire, meme si le net sur le stock est le
     * meme.</p>
     *
     * <p>Symetrie avec la vente : celle-ci deconstate le stock par
     * <b>D 6031 / C 311</b> puis enregistre le produit <b>D 571 / C 70x</b>
     * (voir {@code VenteService.valider}). 6031 recoit ainsi les entrees au
     * credit et les sorties au debit, et se solde en cout des marchandises
     * vendues.</p>
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionCaisseResponse acheterMarchandise(AchatMarchandiseRequest req) {
        User caissier = currentUser.requireUser();

        Article article = stockService.articleParIdInterne(req.articleId());
        if (article.getType() == TypeArticle.SERVICE) {
            throw new IllegalArgumentException(
                "\"" + article.getLibelle() + "\" est un service : il ne peut pas entrer en stock.");
        }
        Entrepot entrepot = stockService.entrepotParIdInterne(req.entrepotId());

        CompteOHADA compteAchat = comptabilite.compteParNumero(req.compteAchatNumero());
        CompteOHADA compteStock = comptabilite.compteParNumero(req.compteStockNumero());
        CompteOHADA compteVariation = comptabilite.compteParNumero(req.compteVariationNumero());
        // Un achat est un decaissement : la contrepartie doit etre une charge
        // (601x). La regle partagee refuserait par exemple un compte de produit.
        regleTresorerie.validerSensContrepartie(SensTransaction.DECAISSEMENT, compteAchat);

        BigDecimal montant = req.prixUnitaire()
            .multiply(req.quantite())
            .setScale(2, RoundingMode.HALF_UP);
        if (montant.signum() <= 0) {
            throw new IllegalArgumentException("Le montant de l'achat doit etre strictement positif.");
        }
        String libelle = StringUtils.hasText(req.libelle())
            ? req.libelle()
            : "Achat " + article.getLibelle() + " x" + req.quantite().stripTrailingZeros().toPlainString();

        // 1. Achat au comptant : D 601x / C 571
        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .montant(montant)
            .sens(SensTransaction.DECAISSEMENT)
            .libelle(libelle)
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(conversionDevise.tauxCourant())
            .build();
        TransactionCaisse saved = comptabilite.enregistrer(transaction, compteAchat, libelle);

        // 2. Entree en stock : D 311x / C 6031
        stockService.enregistrerEntreeAchatInterne(
            LocalDate.now(), libelle,
            new StockService.AchatMarchandise(article, entrepot, req.quantite(), montant,
                compteStock, compteVariation),
            caissier);

        log.info("Achat de marchandise a la caisse [ref={}, article={}, montant={}, par={}]",
            saved.getReference(), article.getCode(), montant, caissier.getEmail());
        return TransactionCaisseResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Reglement des camions de minerais receptionnes
    // ---------------------------------------------------------------------

    /**
     * Solde en especes la dette fournisseur nee de la reception d'un ou
     * plusieurs camions de minerais : <b>D 4011 Fournisseurs / C 571
     * Caisse</b>.
     *
     * <p>Ni le stock ni le resultat ne bougent ici : la marchandise est deja
     * entree (D 311x / C 6031) et l'achat deja constate (D 601x / C 4011) a la
     * reception par la logistique — voir {@code MineraiService.receptionner}.
     * Ce decaissement ne fait que solder la dette, ce qui explique qu'il soit
     * independant de la vente : un camion peut etre paye avant ou apres avoir
     * ete revendu.</p>
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionCaisseResponse reglerCamionsMinerai(ReglementCamionsRequest req) {
        User caissier = currentUser.requireUser();
        CompteOHADA fournisseurs = comptabilite.compteParNumero(MineraiService.COMPTE_FOURNISSEURS);

        List<CamionMinerai> camions = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long id : req.camionIds()) {
            CamionMinerai camion = mineraiService.charger(id);
            if (camion.isRegle()) {
                throw new IllegalArgumentException(
                    "Le camion " + camion.designation() + " est deja regle.");
            }
            camions.add(camion);
            // Le prix d'achat seul : les frais accessoires portent leur propre
            // dette, soldee via chargeIds — les additionner ici les paierait deux fois.
            total = total.add(camion.getPrixAchat());
        }
        List<ChargeCamionMinerai> charges = new ArrayList<>();
        for (Long id : req.chargeIds()) {
            ChargeCamionMinerai charge = mineraiService.chargerCharge(id);
            if (charge.isRegle()) {
                throw new IllegalArgumentException(
                    "La charge « " + charge.getLibelle() + " » du camion "
                    + charge.getCamion().getPlaque() + " est deja reglee.");
            }
            charges.add(charge);
            total = total.add(charge.getMontant());
        }
        if (camions.isEmpty() && charges.isEmpty()) {
            throw new IllegalArgumentException("Aucun camion ni frais a regler.");
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        String libelle = StringUtils.hasText(req.libelle())
            ? req.libelle()
            : "Reglement minerais - " + camions.size() + " camion(s), " + charges.size() + " frais";

        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .montant(total)
            .sens(SensTransaction.DECAISSEMENT)
            .libelle(libelle)
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(conversionDevise.tauxCourant())
            .build();
        TransactionCaisse saved = comptabilite.enregistrer(transaction, fournisseurs, libelle);

        for (CamionMinerai camion : camions) {
            camion.setRegle(true);
            camion.setTransactionReglement(saved);
        }
        for (ChargeCamionMinerai charge : charges) {
            charge.setRegle(true);
            charge.setTransactionReglement(saved);
        }
        mineraiService.marquerReglesInterne(camions, charges);

        log.info("Reglement minerais [camions={}, frais={}, ref={}, montant={}, par={}]",
            camions.size(), charges.size(), saved.getReference(), total, caissier.getEmail());
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

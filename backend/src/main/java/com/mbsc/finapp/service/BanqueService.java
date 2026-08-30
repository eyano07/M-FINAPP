package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TransactionBancaire;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.banque.TransactionBancaireRequest;
import com.mbsc.finapp.dto.banque.TransactionBancaireResponse;
import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.dto.comptabilite.PieceResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.TransactionBancaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Module Banque : enregistrement des operations bancaires et paiement des
 * notes de frais transmises via le compte bancaire (canal alternatif a la
 * caisse). Meme mecanique que {@link CaisseService}, mais sur le compte
 * {@value EcritureComptableService#COMPTE_BANQUE} et le journal
 * {@link JournalComptable#BANQUE} ; la regle de priorite et la ventilation
 * comptable sont mutualisees via {@link RegleTresorerieService}.
 */
@Service
@RequiredArgsConstructor
public class BanqueService {

    private static final Logger log = LoggerFactory.getLogger(BanqueService.class);

    private final TransactionBancaireRepository transactionRepository;
    private final NoteFraisRepository noteRepository;
    private final PieceComptableRepository pieceRepository;
    private final EcritureComptableService comptabilite;
    private final ConversionDeviseService conversionDevise;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final IaAssistantService ia;
    private final RegleTresorerieService regleTresorerie;
    private final StockService stockService;
    private final EtablissementTresorerieService etablissements;
    /** Constate l'ecart de change realise entre engagement et reglement. */
    private final EcartChangeService ecartChange;

    // ---------------------------------------------------------------------
    // Saisie directe
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionBancaireResponse enregistrer(TransactionBancaireRequest req) {
        User operateur = currentUser.requireUser();
        CompteOHADA contrepartie = comptabilite.compteParNumero(req.compteContrepartie());
        regleTresorerie.validerSensContrepartie(req.sens(), contrepartie);

        EtablissementTresorerie banque =
            etablissements.requireEtablissement(req.etablissementId(), TypeEtablissement.BANQUE);
        String libelleAvecBanque = avecEtablissement(req.libelle(), banque);

        TransactionBancaire transaction = TransactionBancaire.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransactionBancaire())
            .montant(req.montant())
            .sens(req.sens())
            .libelle(libelleAvecBanque)
            .operateur(operateur)
            .etablissement(banque)
            .numeroRecu(referenceGenerator.pourRecuBanque())
            .dateOperation(Instant.now())
            .tauxJournalier(conversionDevise.tauxCourant())
            .build();

        TransactionBancaire saved = comptabilite.enregistrerBancaire(
            transaction, banque.getCompte(), contrepartie, libelleAvecBanque, null);
        log.info("Operation bancaire saisie [ref={}, banque={}, par={}]",
            saved.getReference(), banque.getNom(), operateur.getEmail());
        return TransactionBancaireResponse.from(saved);
    }

    /** Ajoute le nom de l'etablissement entre parentheses a la fin du libelle. */
    private String avecEtablissement(String libelle, EtablissementTresorerie etablissement) {
        String base = libelle == null ? "" : libelle.strip();
        return (base.isEmpty() ? "" : base + " ") + "(" + etablissement.getNom() + ")";
    }

    /**
     * Un achat de boissons du module Restaurant doit être réglé par la
     * caisse : c'est ce canal, et lui seul, qui déclenche l'échange de
     * consigne (voir CaisseService.payerNote). Le payer par un autre canal
     * laisserait le stock de bouteilles vides désynchronisé du stock réel.
     */
    private void exigerAbsenceAchatBoisson(NoteFrais note, String libelleCanal) {
        boolean achatBoisson = note.getLignes().stream()
            .anyMatch(l -> l.isAchatMarchandise() && l.getArticle() != null
                && l.getArticle().getType() == TypeArticle.BOISSON);
        if (achatBoisson) {
            throw new TransitionInvalideException(
                "Un achat de boissons doit être réglé par la caisse, pas par " + libelleCanal);
        }
    }

    // ---------------------------------------------------------------------
    // Paiement d'une note de frais (TRANSMISE_CAISSE -> PAYEE)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public TransactionBancaireResponse payerNote(Long noteId, Long etablissementId) {
        User operateur = currentUser.requireUser();
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", noteId));
        EtablissementTresorerie banque =
            etablissements.requireEtablissement(etablissementId, TypeEtablissement.BANQUE);

        if (note.getStatut() != StatutNote.TRANSMISE_CAISSE) {
            throw new TransitionInvalideException(
                "Seule une note TRANSMISE_CAISSE peut etre payee (etat actuel : " + note.getStatut() + ")");
        }
        if (note.estDejaReglee()) {
            throw new TransitionInvalideException(
                "Cette note a deja une transaction de tresorerie associee (caisse, banque ou mobile money)");
        }
        exigerAbsenceAchatBoisson(note, "la banque");

        // Taux résolu une seule fois pour toute l'opération : réutilisé pour
        // les écritures et pour la transaction, afin que taux_journalier et
        // taux_applique ne puissent pas diverger sur un même mouvement.
        BigDecimal tauxOperation = conversionDevise.tauxCourant();
        ConversionDeviseService.Conversion conversion =
            conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxOperation);

        regleTresorerie.validerReglePriorite(
            note, conversion.montantBase(), banque.getCompte().getNumero(), "la banque " + banque.getNom());

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
        libellePaiement = avecEtablissement(libellePaiement, banque);
        TransactionBancaire transaction = TransactionBancaire.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransactionBancaire())
            .noteFrais(note)
            .montant(conversion.montantBase())
            .sens(SensTransaction.DECAISSEMENT)
            .libelle(libellePaiement)
            .operateur(operateur)
            .etablissement(banque)
            .numeroRecu(referenceGenerator.pourRecuBanque())
            .dateOperation(Instant.now())
            .tauxJournalier(tauxOperation)
            .build();

        RegleTresorerieService.VentilationNote ventilation =
            regleTresorerie.construireLignesDebitDepuisNote(note, conversion);
        List<EcritureComptableService.LigneDebit> lignesDebit = ventilation.lignesDebit();

        TransactionBancaire saved = comptabilite.enregistrerDecaissementMultiLigneBancaire(
            transaction, banque.getCompte(), lignesDebit, libellePaiement, conversion);

        // Entree en stock des lignes "achat de marchandise" : rattachee a la
        // piece qui vient d'etre generee, sans nouvelle ecriture (voir
        // StockService.entreesDepuisNoteFraisInterne).
        if (!ventilation.entreesStock().isEmpty()) {
            PieceComptable piece = saved.getEcritures().get(0).getPiece();
            stockService.entreesDepuisNoteFraisInterne(
                saved.getDateOperation().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                libellePaiement, ventilation.entreesStock(), piece, operateur);
        }
        // Ecart de change realise : la note a ete engagee a un taux fige lors
        // de sa transmission, elle est reglee au taux du jour. La difference
        // est reclassee en 676/776 par une piece dediee, au lieu de rester
        // invisible dans le compte de charge.
        ecartChange.comptabiliserEcart(note, tauxOperation,
            saved.getDateOperation().atZone(java.time.ZoneOffset.UTC).toLocalDate(), operateur);

        note.setStatut(StatutNote.PAYEE);
        note.addObservation(com.mbsc.finapp.domain.ObservationNote.builder()
            .noteFrais(note)
            .auteur(operateur)
            .statutAuMoment(StatutNote.PAYEE)
            .commentaire("Paiement execute par virement bancaire (" + banque.getNom()
                + "), recu " + saved.getNumeroRecu())
            .build());

        log.info("Note {} payee par banque {} par {} [recu={}]",
            note.getReference(), banque.getNom(), operateur.getEmail(), saved.getNumeroRecu());
        return TransactionBancaireResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<TransactionBancaireResponse> listerTransactions() {
        return transactionRepository.findAllByOrderByDateEnregistrementDesc().stream()
            .map(TransactionBancaireResponse::from)
            .toList();
    }

    /**
     * Journal de banque : pièces comptabilisées du seul journal BANQUE sur
     * la période, en ordre chronologique — filtré par compte (préfixe 521)
     * réellement mouvementé, pas par étiquette de journal (meme mecanique
     * que le journal de caisse, cf. {@link CaisseService#journal}).
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public LivreJournalResponse journal(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<PieceResponse> pieces = pieceRepository.livreJournalParCompte(EcritureComptableService.PREFIXE_COMPTES_BANQUE, debut, fin)
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
}

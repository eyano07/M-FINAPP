package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TransactionMobileMoney;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.dto.comptabilite.PieceResponse;
import com.mbsc.finapp.dto.mobilemoney.TransactionMobileMoneyRequest;
import com.mbsc.finapp.dto.mobilemoney.TransactionMobileMoneyResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.TransactionMobileMoneyRepository;
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
 * Module Mobile Money : enregistrement des operations et paiement des notes
 * de frais transmises via un canal mobile money (alternatif a la caisse et
 * a la banque). Meme mecanique que {@link CaisseService}, mais sur le
 * compte {@value EcritureComptableService#COMPTE_MOBILE_MONEY} et le
 * journal {@link JournalComptable#MOBILE_MONEY} ; la regle de priorite et
 * la ventilation comptable sont mutualisees via {@link RegleTresorerieService}.
 */
@Service
@RequiredArgsConstructor
public class MobileMoneyService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyService.class);

    private final TransactionMobileMoneyRepository transactionRepository;
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
    public TransactionMobileMoneyResponse enregistrer(TransactionMobileMoneyRequest req) {
        User operateur = currentUser.requireUser();
        CompteOHADA contrepartie = comptabilite.compteParNumero(req.compteContrepartie());
        regleTresorerie.validerSensContrepartie(req.sens(), contrepartie);

        EtablissementTresorerie operateurMM =
            etablissements.requireEtablissement(req.etablissementId(), TypeEtablissement.MOBILE_MONEY);
        String libelleAvecOperateur = avecEtablissement(req.libelle(), operateurMM);

        TransactionMobileMoney transaction = TransactionMobileMoney.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransactionMobileMoney())
            .montant(req.montant())
            .sens(req.sens())
            .libelle(libelleAvecOperateur)
            .operateur(operateur)
            .etablissement(operateurMM)
            .numeroRecu(referenceGenerator.pourRecuMobileMoney())
            .dateOperation(Instant.now())
            .tauxJournalier(conversionDevise.tauxCourant())
            .build();

        TransactionMobileMoney saved = comptabilite.enregistrerMobileMoney(
            transaction, operateurMM.getCompte(), contrepartie, libelleAvecOperateur, null);
        log.info("Operation mobile money saisie [ref={}, operateur={}, par={}]",
            saved.getReference(), operateurMM.getNom(), operateur.getEmail());
        return TransactionMobileMoneyResponse.from(saved);
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
    public TransactionMobileMoneyResponse payerNote(Long noteId, Long etablissementId) {
        User operateur = currentUser.requireUser();
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", noteId));
        EtablissementTresorerie operateurMM =
            etablissements.requireEtablissement(etablissementId, TypeEtablissement.MOBILE_MONEY);

        if (note.getStatut() != StatutNote.TRANSMISE_CAISSE) {
            throw new TransitionInvalideException(
                "Seule une note TRANSMISE_CAISSE peut etre payee (etat actuel : " + note.getStatut() + ")");
        }
        if (note.estDejaReglee()) {
            throw new TransitionInvalideException(
                "Cette note a deja une transaction de tresorerie associee (caisse, banque ou mobile money)");
        }
        exigerAbsenceAchatBoisson(note, "le mobile money");

        // Taux résolu une seule fois pour toute l'opération : réutilisé pour
        // les écritures et pour la transaction, afin que taux_journalier et
        // taux_applique ne puissent pas diverger sur un même mouvement.
        BigDecimal tauxOperation = conversionDevise.tauxCourant();
        ConversionDeviseService.Conversion conversion =
            conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxOperation);

        regleTresorerie.validerReglePriorite(
            note, conversion.montantBase(), operateurMM.getCompte().getNumero(),
            "le compte mobile money " + operateurMM.getNom());

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
        libellePaiement = avecEtablissement(libellePaiement, operateurMM);
        TransactionMobileMoney transaction = TransactionMobileMoney.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransactionMobileMoney())
            .noteFrais(note)
            .montant(conversion.montantBase())
            .sens(SensTransaction.DECAISSEMENT)
            .libelle(libellePaiement)
            .operateur(operateur)
            .etablissement(operateurMM)
            .numeroRecu(referenceGenerator.pourRecuMobileMoney())
            .dateOperation(Instant.now())
            .tauxJournalier(tauxOperation)
            .build();

        RegleTresorerieService.VentilationNote ventilation =
            regleTresorerie.construireLignesDebitDepuisNote(note, conversion);
        List<EcritureComptableService.LigneDebit> lignesDebit = ventilation.lignesDebit();

        TransactionMobileMoney saved = comptabilite.enregistrerDecaissementMultiLigneMobileMoney(
            transaction, operateurMM.getCompte(), lignesDebit, libellePaiement, conversion);

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
            .commentaire("Paiement execute par mobile money (" + operateurMM.getNom()
                + "), recu " + saved.getNumeroRecu())
            .build());

        log.info("Note {} payee par mobile money ({}) par {} [recu={}]",
            note.getReference(), operateurMM.getNom(), operateur.getEmail(), saved.getNumeroRecu());
        return TransactionMobileMoneyResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<TransactionMobileMoneyResponse> listerTransactions() {
        return transactionRepository.findAllByOrderByDateEnregistrementDesc().stream()
            .map(TransactionMobileMoneyResponse::from)
            .toList();
    }

    /**
     * Journal mobile money : pièces comptabilisées du seul journal
     * MOBILE_MONEY sur la période, en ordre chronologique — filtré par
     * compte (préfixe 552) réellement mouvementé, pas par étiquette de
     * journal (meme mecanique que le journal de caisse, cf.
     * {@link CaisseService#journal}).
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public LivreJournalResponse journal(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<PieceResponse> pieces = pieceRepository.livreJournalParCompte(EcritureComptableService.PREFIXE_COMPTES_MOBILE_MONEY, debut, fin)
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

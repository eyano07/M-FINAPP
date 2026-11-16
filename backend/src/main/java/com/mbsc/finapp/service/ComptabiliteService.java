package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.SoldeOuverture;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.comptabilite.*;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Module comptabilité générale : pièces comptables équilibrées, grand livre par compte,
 * balance de vérification (inspiré ERPNext Journal Entry).
 */
@Service
@RequiredArgsConstructor
public class ComptabiliteService {

    private static final Logger log = LoggerFactory.getLogger(ComptabiliteService.class);

    /** Classe SYSCOHADA des engagements hors bilan et de la comptabilité analytique : hors du bilan et du résultat. */
    private static final int CLASSE_HORS_BILAN = 9;

    private final PieceComptableRepository pieceRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final CompteOHADARepository compteRepository;
    /** Détectent une pièce d'origine stock/vente — voir {@link #annuler}. */
    private final MouvementStockRepository mouvementStockRepository;
    private final VenteRepository venteRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final PeriodeComptableService periodeService;
    private final ConversionDeviseService conversionDevise;
    private final com.mbsc.finapp.repository.ReleveBancaireRepository releveRepository;
    /** Piste d'audit en ajout seul des actions sur les pièces (audit SYSCOHADA du 10/11/2026, C-09). */
    private final JournalEvenementsComptables evenements;
    /**
     * Circuit comptable (demande du 13/11/2026) : le DFIN est prévenu de ce que fait le comptable (saisie, restauration
     * en brouillon, annulation), et le comptable de ce que fait le DFIN ou l'administrateur sur ses pièces
     * (comptabilisation, comptabilisation rétablie).
     */
    private final NotificationService notifications;

    // ---------------------------------------------------------------------
    // Pièces comptables
    // ---------------------------------------------------------------------

    // Audit SYSCOHADA du 10/11/2026, C-06 : le comptable saisit, le DFIN valide.
    @PreAuthorize("hasRole('COMPTABLE')")
    @Transactional
    public PieceResponse creerPiece(PieceCreateRequest req) {
        User auteur = currentUser.requireUser();
        validerLignes(req.lignes());
        exigerHorsTresorerie(req.journal(), req.lignes().stream().map(LigneEcritureRequest::compteNumero).toList());

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (LigneEcritureRequest l : req.lignes()) {
            totalDebit = totalDebit.add(nz(l.debit()));
            totalCredit = totalCredit.add(nz(l.credit()));
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                "La pièce n'est pas équilibrée : débit=" + totalDebit + ", crédit=" + totalCredit);
        }

        LocalDate datePiece = req.datePiece() != null ? req.datePiece() : LocalDate.now();
        periodeService.verifierDateOuverte(datePiece);

        // Garde-fou anti double-soumission (audit du 18/08/2026, A-07) : un
        // double-clic ou une re-soumission réseau reproduit exactement les
        // mêmes journal/date/libellé/total dans les toutes prochaines
        // secondes — une intention réelle de saisir deux pièces identiques
        // à la même seconde est, elle, quasi inexistante.
        long recentes = pieceRepository.compterRecentesIdentiques(
            auteur.getId(), req.journal(), datePiece, req.libelle(), totalDebit,
            Instant.now().minusSeconds(10));
        if (recentes > 0) {
            throw new IllegalStateException(
                "Une pièce identique vient d'être créée à l'instant — double soumission probable. "
                + "Vérifiez la liste des pièces avant de réessayer.");
        }
        // Numéro provisoire : le numéro définitif, continu par exercice, est attribué à la validation (C-05).
        PieceComptable piece = PieceComptable.builder()
            .reference(referenceGenerator.pourBrouillon())
            .datePiece(datePiece)
            .journal(req.journal())
            .libelle(req.libelle())
            .beneficiaire(StringUtils.hasText(req.beneficiaire()) ? req.beneficiaire().trim() : null)
            .statut(StatutPiece.BROUILLON)
            .soldeOuverture(estSoldeOuverture(req, datePiece))
            .totalDebit(totalDebit)
            .totalCredit(totalCredit)
            .createdBy(auteur)
            .build();

        for (LigneEcritureRequest l : req.lignes()) {
            CompteOHADA compte = compteRepository.findByNumero(l.compteNumero())
                .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", l.compteNumero()));
            exigerCompteImputable(compte);
            String libelleLigne = StringUtils.hasText(l.libelle()) ? l.libelle() : req.libelle();
            piece.addLigne(EcritureGrandLivre.builder()
                .compte(compte)
                .debit(nz(l.debit()))
                .credit(nz(l.credit()))
                .libelle(libelleLigne)
                .dateEcriture(datePiece)
                .build());
        }

        PieceComptable saved = pieceRepository.save(piece);
        evenements.piece("CREATION", saved, null, null, auteur);
        log.info("Pièce comptable créée [ref={}, par={}]", saved.getReference(), auteur.getEmail());
        notifications.notifierPiece(List.of(RoleType.DFIN), null, auteur, TypeNotification.PIECE_A_COMPTABILISER,
            "Pièce à comptabiliser",
            nom(auteur) + " a saisi la pièce " + saved.getReference() + " " + resume(saved) + ". Elle attend votre validation.",
            saved.getId());
        return PieceResponse.from(saved);
    }

    /**
     * Modifie une pièce encore au BROUILLON (façon Sage : le brouillard reste
     * corrigeable tant qu'il n'est pas validé). Une pièce comptabilisée est
     * immuable : seule l'extourne est possible.
     */
    @PreAuthorize("hasRole('COMPTABLE')")
    @Transactional
    public PieceResponse modifierPiece(Long id, PieceCreateRequest req) {
        PieceComptable piece = chargerAvecLignes(id);
        if (piece.getStatut() != StatutPiece.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une pièce BROUILLON peut être modifiée (état actuel : " + piece.getStatut() + ")");
        }
        validerLignes(req.lignes());
        exigerHorsTresorerie(req.journal(), req.lignes().stream().map(LigneEcritureRequest::compteNumero).toList());

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (LigneEcritureRequest l : req.lignes()) {
            totalDebit = totalDebit.add(nz(l.debit()));
            totalCredit = totalCredit.add(nz(l.credit()));
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                "La pièce n'est pas équilibrée : débit=" + totalDebit + ", crédit=" + totalCredit);
        }

        LocalDate datePiece = req.datePiece() != null ? req.datePiece() : piece.getDatePiece();
        periodeService.verifierDateOuverte(datePiece);
        java.util.Map<String, Object> avant = JournalEvenementsComptables.instantane(piece);

        piece.setDatePiece(datePiece);
        piece.setJournal(req.journal());
        piece.setLibelle(req.libelle());
        piece.setBeneficiaire(StringUtils.hasText(req.beneficiaire()) ? req.beneficiaire().trim() : null);
        piece.setSoldeOuverture(estSoldeOuverture(req, datePiece));
        piece.setTotalDebit(totalDebit);
        piece.setTotalCredit(totalCredit);
        piece.getLignes().clear();
        for (LigneEcritureRequest l : req.lignes()) {
            CompteOHADA compte = compteRepository.findByNumero(l.compteNumero())
                .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", l.compteNumero()));
            exigerCompteImputable(compte);
            String libelleLigne = StringUtils.hasText(l.libelle()) ? l.libelle() : req.libelle();
            piece.addLigne(EcritureGrandLivre.builder()
                .compte(compte)
                .debit(nz(l.debit()))
                .credit(nz(l.credit()))
                .libelle(libelleLigne)
                .dateEcriture(datePiece)
                .build());
        }
        evenements.piece("MODIFICATION", piece, avant, null, currentUser.requireUser());
        log.info("Pièce brouillon modifiée [ref={}]", piece.getReference());
        return PieceResponse.from(piece);
    }

    /**
     * Bascule uniquement le marqueur « solde d'ouverture » d'une pièce, sans
     * toucher à ses montants, comptes ou date — contrairement à
     * {@link #modifierPiece}, disponible même sur une pièce COMPTABILISEE :
     * ce marqueur ne pilote que la colonne (ouverture/mouvements) où la
     * balance et le compte de résultat rangent la pièce, il n'affecte ni
     * l'équilibre de la pièce ni son montant, donc pas l'intégrité du
     * grand livre déjà posté.
     *
     * <p>Corrige le cas réel qui a motivé cet ajout : une reprise des
     * à-nouveaux saisie via l'écran de pièce standard (qui porte pourtant la
     * case à cocher) sans que l'utilisateur ne l'ait cochée — jusqu'ici,
     * seule une intervention en base pouvait la reclasser après
     * comptabilisation.</p>
     */
    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public PieceResponse basculerSoldeOuverture(Long id, boolean soldeOuverture, String motif) {
        PieceComptable piece = chargerAvecLignes(id);
        if (piece.isSoldeOuverture() == soldeOuverture) {
            return PieceResponse.from(piece);
        }
        // Audit SYSCOHADA du 10/11/2026, C-09 : reclasser une pièce comptabilisée change la balance et le
        // résultat d'une période ; elle doit donc être ouverte, et le motif est gardé au journal des événements.
        if (piece.getStatut() != StatutPiece.BROUILLON) {
            periodeService.verifierDateOuverte(piece.getDatePiece());
            if (!StringUtils.hasText(motif)) {
                throw new IllegalArgumentException(
                    "Indiquez le motif du reclassement : la pièce " + piece.getReference() + " est déjà comptabilisée.");
            }
        }
        java.util.Map<String, Object> avant = JournalEvenementsComptables.instantane(piece);
        piece.setSoldeOuverture(soldeOuverture);
        evenements.piece("BASCULE_SOLDE_OUVERTURE", piece, avant, motif, currentUser.requireUser());
        log.info("Marqueur solde d'ouverture {} [ref={}]", soldeOuverture ? "active" : "desactive", piece.getReference());
        return PieceResponse.from(piece);
    }

    /** Supprime une pièce encore au BROUILLON (jamais une pièce comptabilisée). */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN')")
    @Transactional
    public void supprimerPiece(Long id) {
        PieceComptable piece = chargerAvecLignes(id);
        if (piece.getStatut() != StatutPiece.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une pièce BROUILLON peut être supprimée (état actuel : " + piece.getStatut()
                + "). Une pièce comptabilisée doit être extournée.");
        }
        java.util.Map<String, Object> avant = JournalEvenementsComptables.instantane(piece);
        Long idPiece = piece.getId();
        String reference = piece.getReference();
        pieceRepository.delete(piece);
        evenements.enregistrer("SUPPRESSION_BROUILLON", "PIECE", idPiece, reference, avant, null, null,
            currentUser.requireUser());
        log.info("Pièce brouillon supprimée [ref={}]", reference);
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<PieceResponse> listerPieces() {
        // L'écran ne propose la comptabilisation et l'extourne que là où
        // comptabiliser() et annuler() les accepteront.
        java.util.Set<Long> liees = new java.util.HashSet<>(pieceRepository.idsPiecesLieesAUneOperation());
        java.util.Set<Long> tresorerie = new java.util.HashSet<>(pieceRepository.idsPiecesTresorerie());
        java.util.Set<Long> restaurees = new java.util.HashSet<>(pieceRepository.idsPiecesRestaurees());
        java.util.Set<Long> extournees = new java.util.HashSet<>(pieceRepository.idsPiecesAvecExtourneEnVigueur());
        return pieceRepository.findAllWithCreatedByOrderByDatePieceDesc().stream()
            .map(p -> {
                boolean touche = tresorerie.contains(p.getId());
                return PieceResponse.from(p, false,
                    p.getStatut() == StatutPiece.COMPTABILISEE && !liees.contains(p.getId()) && !touche,
                    p.getStatut() == StatutPiece.BROUILLON && !touche && !JOURNAUX_TRESORERIE.contains(p.getJournal()),
                    touche,
                    estRestaurable(p, touche, liees.contains(p.getId()), restaurees.contains(p.getId()),
                        extournees.contains(p.getId())));
            })
            .toList();
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public PieceResponse consulterPiece(Long id) {
        return PieceResponse.from(chargerAvecLignes(id));
    }

    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public PieceResponse comptabiliser(Long id) {
        PieceComptable piece = chargerAvecLignes(id);
        if (piece.getStatut() != StatutPiece.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une pièce BROUILLON peut être comptabilisée (état actuel : " + piece.getStatut() + ")");
        }
        // Un brouillon saisi avant cette règle ne doit pas pouvoir la
        // contourner au moment de sa validation.
        exigerHorsTresorerie(piece.getJournal(), piece.getLignes().stream().map(l -> l.getCompte().getNumero()).toList());
        // Audit SYSCOHADA du 10/11/2026, C-26 : un brouillon daté d'une période close ne se comptabilise plus
        // après la clôture (la base le refuse aussi, V135).
        periodeService.verifierDateOuverte(piece.getDatePiece());
        // C-06 : celui qui a saisi la pièce ne la valide pas lui-même, sauf dérogation posée par le DA ou le DG.
        User valideur = currentUser.requireUser();
        if (piece.getCreatedBy() != null && piece.getCreatedBy().getId() != null
                && piece.getCreatedBy().getId().equals(valideur.getId())
                && !periodeService.autoValidationAutorisee()) {
            throw new TransitionInvalideException("Vous avez saisi la pièce " + piece.getReference()
                + " : elle doit être comptabilisée par une autre personne (séparation des tâches).");
        }
        // Le brouillon peut avoir été saisi puis validé plusieurs jours après :
        // c'est le taux du jour de la VALIDATION (pas de la saisie) qui fige
        // durablement chaque écriture en FC pour son affichage ultérieur en USD.
        BigDecimal tauxDuJour = conversionDevise.tauxCourant();
        for (EcritureGrandLivre ligne : piece.getLignes()) {
            if (ligne.getTauxApplique() == null) {
                ligne.setTauxApplique(tauxDuJour);
            }
        }
        verifierNonRapproche(piece.getLignes(), piece.getDatePiece());
        java.util.Map<String, Object> avant = JournalEvenementsComptables.instantane(piece);
        String referenceSaisie = piece.getReference();
        if (ReferenceGenerator.estProvisoire(piece.getReference())) {
            piece.setReference(referenceGenerator.pourPieceDefinitive(piece.getDatePiece()));
        }
        piece.setStatut(StatutPiece.COMPTABILISEE);
        piece.setComptabiliseePar(valideur);
        piece.setComptabiliseeLe(Instant.now());
        evenements.piece("COMPTABILISATION", piece, avant, null, valideur);
        log.info("Pièce comptabilisée [ref={}]", piece.getReference());
        String brouillon = Objects.equals(referenceSaisie, piece.getReference()) ? ""
            : " Numéro du brouillon : " + referenceSaisie + ".";
        String reprise = piece.getPieceRestauree() == null ? ""
            : " Elle rétablit la pièce annulée " + piece.getPieceRestauree().getReference() + ".";
        prevenirComptable(piece.getCreatedBy(), valideur, TypeNotification.PIECE_COMPTABILISEE, "Pièce comptabilisée",
            nom(valideur) + " a comptabilisé la pièce " + piece.getReference() + " " + resume(piece) + "." + brouillon
            + reprise, piece.getId(), List.of());
        return PieceResponse.from(piece);
    }

    /**
     * Extourne une pièce depuis l'écran Comptabilité.
     *
     * <p>Refuse toute pièce portant un mouvement de stock (donc, par
     * transitivité, une production — qui n'a pas de pièce propre mais deux
     * mouvements) ou une vente : ces pièces sont indissociables d'un état
     * métier (niveau de stock, statut de production, statut de vente) que
     * seul l'écran d'origine sait rejouer en cohérence. Une extourne directe
     * ici solderait le grand livre sans toucher {@code stock_niveaux} ni le
     * mouvement lui-même (resté VALIDE) : le module concerné continuerait
     * d'afficher — et de vendre — un stock qui n'existe plus comptablement.</p>
     *
     * <p>Refuse de même toute pièce portant une opération de trésorerie
     * (caisse, banque, mobile money) : l'opération resterait inscrite à son
     * journal, la note de frais qu'elle règle resterait payée et la
     * marchandise achetée resterait en stock, alors que le grand livre, lui,
     * n'en garderait aucune trace. Une imputation erronée se corrige par une
     * pièce de reclassement, un montant erroné par une note de frais.</p>
     *
     * <p>Refuse enfin toute pièce qui mouvemente un compte de trésorerie sans
     * opération derrière elle (journal historique importé) : voir
     * {@link #exigerHorsTresorerie}. Le DFIN ne s'en charge plus depuis la
     * règle du 13/11/2026.</p>
     *
     * <p>Seul le comptable annule une comptabilisation (demande de l'utilisateur,
     * 13/11/2026) : le DFIN et l'administrateur la rétablissent, par
     * {@link #restaurer}, s'ils jugent l'annulation injustifiée. La pièce reste,
     * ANNULEE, et son extourne la solde, comme le veut le SYSCOHADA révisé.</p>
     */
    @PreAuthorize("hasRole('COMPTABLE')")
    @Transactional
    public PieceResponse annuler(Long id) {
        PieceComptable originale = chargerAvecLignes(id);
        if (mouvementStockRepository.existsAvecPieceId(id) || venteRepository.existsAvecPieceId(id)) {
            throw new IllegalStateException(
                "Cette pièce (" + originale.getReference() + ") est liée à un mouvement de stock ou à une "
                + "vente : elle ne peut être extournée que depuis son écran d'origine "
                + "(Logistique, Production, Restaurant ou Ventes), pas depuis la Comptabilité.");
        }
        if (ecritureRepository.existsTresorerieParPieceId(id)) {
            throw new IllegalStateException(
                "Cette pièce (" + originale.getReference() + ") enregistre une opération de caisse, de banque "
                + "ou de mobile money : l'extourner seule laisserait l'opération à son journal. Corrigez une "
                + "imputation par une pièce de reclassement ; un montant, par une note de frais d'encaissement "
                + "ou de décaissement.");
        }
        // Même sans opération de trésorerie derrière elle (journal historique
        // importé), une pièce qui mouvemente la classe 5 ne s'extourne pas
        // d'ici : ce serait modifier la trésorerie sans note de frais.
        if (originale.getLignes().stream().anyMatch(l -> estCompteTresorerie(l.getCompte().getNumero()))) {
            throw new IllegalStateException(
                "Cette pièce (" + originale.getReference() + ") mouvemente la trésorerie : l'extourner d'ici "
                + "modifierait la caisse, la banque ou le mobile money sans passer par une note de frais. "
                + "Une correction de trésorerie passe par une note de frais d'encaissement ou de décaissement.");
        }
        User auteur = currentUser.requireUser();
        PieceComptable extourne = extournerInterne(originale, auteur);
        notifications.notifierPiece(List.of(RoleType.DFIN), null, auteur, TypeNotification.PIECE_ANNULEE, "Pièce annulée",
            nom(auteur) + " a annulé la pièce " + originale.getReference() + " " + resume(originale) + " : l'extourne "
            + extourne.getReference() + " la solde."
            + (originale.getPieceOrigine() == null
                ? " Si l'annulation n'est pas justifiée, rétablissez la comptabilisation avec « Restaurer »." : ""),
            originale.getId());
        return PieceResponse.from(originale);
    }

    /**
     * Restaure une pièce annulée par erreur (demandes de l'utilisateur, 12 et 13/11/2026) : une pièce annulée ne
     * revit pas — l'annulation et son extourne restent au journal, comme le veut le SYSCOHADA —, mais on obtient une
     * nouvelle pièce identique à la pièce d'origine, reliée à elle :
     * <ul>
     *   <li>le comptable, qui saisit, obtient un <b>brouillon</b> que le DFIN valide comme toute autre pièce ;</li>
     *   <li>le DFIN et l'administrateur, qui contrôlent, <b>rétablissent la comptabilisation</b> d'un coup : la
     *       nouvelle pièce est comptabilisée tout de suite, aux mêmes comptes et aux mêmes montants. Rien n'étant
     *       ressaisi, il n'y a rien d'autre à valider (et le DFIN ne pourrait pas valider sa propre pièce, C-06).</li>
     * </ul>
     * Seulement pour une pièce saisie en comptabilité (ni stock, ni vente, ni trésorerie) dont l'annulation est
     * encore en vigueur (pas d'extourne d'extourne), et une seule restauration à la fois.
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public PieceResponse restaurer(Long id) {
        PieceComptable originale = chargerAvecLignes(id);
        boolean touche = originale.getLignes().stream().anyMatch(l -> estCompteTresorerie(l.getCompte().getNumero()));
        boolean liee = mouvementStockRepository.existsAvecPieceId(id) || venteRepository.existsAvecPieceId(id)
            || ecritureRepository.existsTresorerieParPieceId(id);
        if (!estRestaurable(originale, touche, liee, pieceRepository.existsRestaurationActive(id),
                pieceRepository.existsExtourneEnVigueur(id))) {
            throw new TransitionInvalideException("La pièce " + originale.getReference() + " ne peut pas être restaurée : "
                + "seule une pièce annulée depuis la comptabilité, sans trésorerie, stock ni vente, dont l'annulation "
                + "est encore en vigueur et qui n'est pas déjà restaurée, se restaure.");
        }
        User auteur = currentUser.requireUser();
        boolean retablie = retablitDirectement();
        LocalDate date = dateExtourne(originale.getDatePiece());
        periodeService.verifierDateOuverte(date);
        String libelle = "Restauration de " + originale.getReference()
            + (StringUtils.hasText(originale.getLibelle()) ? " - " + originale.getLibelle() : "");
        PieceComptable copie = PieceComptable.builder()
            .reference(retablie ? referenceGenerator.pourPieceDefinitive(date) : referenceGenerator.pourBrouillon())
            .datePiece(date)
            .journal(originale.getJournal())
            .libelle(libelle.length() > 255 ? libelle.substring(0, 255) : libelle)
            .statut(retablie ? StatutPiece.COMPTABILISEE : StatutPiece.BROUILLON)
            .soldeOuverture(originale.isSoldeOuverture())
            .totalDebit(originale.getTotalDebit())
            .totalCredit(originale.getTotalCredit())
            .createdBy(auteur)
            .pieceRestauree(originale)
            .build();
        if (retablie) {
            copie.setComptabiliseePar(auteur);
            copie.setComptabiliseeLe(Instant.now());
        }
        // Un brouillon reçoit son taux à la validation ; une pièce rétablie garde celui de la pièce d'origine (à défaut,
        // le taux du jour), comme son extourne : la contre-valeur reste figée telle qu'à la première comptabilisation.
        BigDecimal tauxDuJour = retablie ? conversionDevise.tauxCourant() : null;
        for (EcritureGrandLivre l : originale.getLignes()) {
            exigerCompteImputable(l.getCompte());
            copie.addLigne(EcritureGrandLivre.builder()
                .compte(l.getCompte()).debit(l.getDebit()).credit(l.getCredit()).libelle(l.getLibelle())
                .dateEcriture(date).devise(l.getDevise()).montantDevise(l.getMontantDevise())
                .tauxApplique(retablie ? (l.getTauxApplique() != null ? l.getTauxApplique() : tauxDuJour) : null)
                .build());
        }
        PieceComptable saved = pieceRepository.save(copie);
        evenements.piece("RESTAURATION", saved, null,
            (retablie ? "Comptabilisation rétablie de " : "Restauration de ") + originale.getReference(), auteur);
        log.info("Pièce {} {} [ref={}]", originale.getReference(),
            retablie ? "rétablie en comptabilité" : "restaurée en brouillon", saved.getReference());
        if (retablie) {
            // Le comptable qui avait annulé la pièce (auteur de son extourne) apprend que l'annulation est levée ; le DFIN
            // aussi, quand c'est l'administrateur qui la lève.
            User annulateur = pieceRepository.findFirstByPieceOrigine_IdAndStatutOrderByIdDesc(id, StatutPiece.COMPTABILISEE)
                .map(PieceComptable::getCreatedBy).orElse(null);
            prevenirComptable(annulateur, auteur, TypeNotification.PIECE_RESTAUREE, "Comptabilisation rétablie",
                nom(auteur) + " a rétabli la comptabilisation de la pièce " + originale.getReference() + " "
                + resume(originale) + " : la nouvelle pièce " + saved.getReference() + " la reprend. L'annulation et son "
                + "extourne restent au journal.", saved.getId(), aLeRole("DFIN") ? List.of() : List.of(RoleType.DFIN));
        } else {
            notifications.notifierPiece(List.of(RoleType.DFIN), null, auteur, TypeNotification.PIECE_A_COMPTABILISER,
                "Restauration à comptabiliser",
                nom(auteur) + " a restauré la pièce annulée " + originale.getReference() + " " + resume(originale)
                + " en un nouveau brouillon " + saved.getReference() + ". Il attend votre validation.", saved.getId());
        }
        return PieceResponse.from(saved);
    }

    /**
     * Pièce d'origine annulée, saisie en comptabilité, dont l'extourne est encore comptabilisée et qui n'a pas de
     * restauration active : sans extourne en vigueur, la comptabilisation est déjà rétablie (extourne d'extourne).
     */
    private static boolean estRestaurable(PieceComptable p, boolean tresorerie, boolean liee, boolean dejaRestauree,
                                          boolean extourneEnVigueur) {
        return p.getStatut() == StatutPiece.ANNULEE && p.getPieceOrigine() == null && !p.isClotureExercice()
            && !p.isReevaluationDevise() && !tresorerie && !liee && !dejaRestauree && extourneEnVigueur
            && !JOURNAUX_TRESORERIE.contains(p.getJournal());
    }

    /**
     * Le DFIN et l'administrateur rétablissent la comptabilisation d'une pièce annulée en une seule action ; le
     * comptable n'obtient qu'un brouillon à faire valider.
     */
    private boolean retablitDirectement() {
        return aLeRole("DFIN") || aLeRole("ADMIN");
    }

    private boolean aLeRole(String role) {
        return currentUser.requirePrincipal().getAuthorities().stream()
            .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    /**
     * Prévient le comptable concerné ({@code concerne} : celui qui a saisi la pièce, ou qui l'a annulée) et les rôles
     * {@code aussi}. Si cette personne est inconnue, inactive, n'est pas comptable ou fait elle-même l'action, tous les
     * comptables actifs sont prévenus (et elle aussi, si elle est active et n'est pas l'auteur de l'action).
     */
    private void prevenirComptable(User concerne, User acteur, TypeNotification type, String titre, String message,
                                   Long pieceId, List<RoleType> aussi) {
        boolean cible = concerne != null && concerne.isActif()
            && (acteur == null || !Objects.equals(concerne.getId(), acteur.getId()))
            && concerne.getRoles().stream().anyMatch(r -> r.getNom() == RoleType.COMPTABLE);
        List<RoleType> roles = new ArrayList<>(aussi);
        if (!cible) {
            roles.add(RoleType.COMPTABLE);
        }
        notifications.notifierPiece(roles, concerne, acteur, type, titre, message, pieceId);
    }

    private static final DateTimeFormatter DATE_NOTIFICATION = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** « du 13/11/2026 : Pécules congés (1 440,00 USD) », pour les notifications. */
    static String resume(PieceComptable p) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.FRANCE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        String date = p.getDatePiece() == null ? "" : "du " + p.getDatePiece().format(DATE_NOTIFICATION);
        String libelle = StringUtils.hasText(p.getLibelle()) ? " : " + p.getLibelle().strip() : "";
        String montant = p.getTotalDebit() == null ? ""
            : " (" + format.format(p.getTotalDebit()) + " " + ConversionDeviseService.DEVISE_BASE + ")";
        return (date + libelle + montant).strip();
    }

    /** « Jean KABILA », à défaut l'adresse de connexion. */
    static String nom(User u) {
        if (u == null) {
            return "Un utilisateur";
        }
        String complet = ((u.getPrenom() == null ? "" : u.getPrenom().strip()) + " "
            + (u.getNom() == null ? "" : u.getNom().strip())).strip();
        return complet.isEmpty() ? u.getEmail() : complet;
    }

    /**
     * Extourne (contre-passation) accessible aux services métier internes
     * (stock, transport) sans exigence de rôle : la sécurité est portée par
     * l'opération appelante. Ne jamais exposer directement via un contrôleur.
     */
    @Transactional
    public PieceComptable annulerInterne(Long pieceId, User auteur) {
        PieceComptable originale = chargerAvecLignes(pieceId);
        extournerInterne(originale, auteur);
        return originale;
    }

    /**
     * Date d'une écriture qui corrige une opération passée : sa propre date,
     * sauf si la période est clôturée — elle bascule alors à aujourd'hui pour
     * rester dans une période ouverte. Règle de l'extourne, partagée avec les
     * pièces qui l'accompagnent (écart de valorisation à l'annulation d'un
     * mouvement de stock).
     */
    public LocalDate dateExtourne(LocalDate dateOrigine) {
        LocalDate dateCloture = periodeService.dateCloture();
        if (dateCloture != null && !dateOrigine.isAfter(dateCloture)) {
            return LocalDate.now();
        }
        return dateOrigine;
    }

    /** Annule {@code originale} (statut ANNULEE) par une pièce d'extourne comptabilisée, qui est renvoyée. */
    private PieceComptable extournerInterne(PieceComptable originale, User auteur) {
        if (originale.getStatut() != StatutPiece.COMPTABILISEE) {
            throw new TransitionInvalideException(
                "Seule une pièce COMPTABILISEE peut être annulée (état actuel : " + originale.getStatut() + ")");
        }

        LocalDate dateExtourne = dateExtourne(originale.getDatePiece());
        periodeService.verifierDateOuverte(dateExtourne);

        String libelleExtourne = "Extourne " + originale.getReference()
            + (StringUtils.hasText(originale.getLibelle()) ? " - " + originale.getLibelle() : "");

        PieceComptable extourne = PieceComptable.builder()
            .reference(referenceGenerator.pourPieceDefinitive(dateExtourne))
            .datePiece(dateExtourne)
            .journal(originale.getJournal())
            .libelle(libelleExtourne)
            .statut(StatutPiece.COMPTABILISEE)
            // L'extourne d'une reprise des à-nouveaux reste dans les soldes d'ouverture : la reprise annulée en sort,
            // au lieu de laisser l'ouverture intacte et une annulation dans les mouvements de la période.
            .soldeOuverture(originale.isSoldeOuverture())
            .totalDebit(originale.getTotalCredit())
            .totalCredit(originale.getTotalDebit())
            .createdBy(auteur)
            .comptabiliseePar(auteur)
            .comptabiliseeLe(Instant.now())
            .pieceOrigine(originale)
            .build();

        for (EcritureGrandLivre ligne : originale.getLignes()) {
            extourne.addLigne(EcritureGrandLivre.builder()
                .compte(ligne.getCompte())
                .debit(ligne.getCredit())
                .credit(ligne.getDebit())
                .libelle(libelleExtourne)
                .dateEcriture(dateExtourne)
                .devise(ligne.getDevise())
                .montantDevise(ligne.getMontantDevise())
                .tauxApplique(ligne.getTauxApplique())
                .build());
        }

        java.util.Map<String, Object> avant = JournalEvenementsComptables.instantane(originale);
        originale.setStatut(StatutPiece.ANNULEE);
        pieceRepository.save(extourne);
        evenements.piece("EXTOURNE", originale, avant, "Extourne " + extourne.getReference(), auteur);
        log.info("Pièce {} annulée par extourne [ref={}]", originale.getReference(), extourne.getReference());
        return extourne;
    }

    /**
     * Crée une pièce comptabilisée automatiquement (flux caisse).
     */
    @Transactional
    public PieceComptable creerPieceCaisse(String libelle, LocalDate datePiece,
                                           List<EcritureGrandLivre> lignes, User auteur) {
        return creerPieceInterne(JournalComptable.CAISSE, libelle, datePiece, lignes, auteur);
    }

    /**
     * Crée une pièce comptabilisée automatiquement pour un journal donné
     * (flux internes : caisse, stock, transport...). Les lignes sont supposées
     * déjà équilibrées par l'appelant.
     */
    @Transactional
    public PieceComptable creerPieceInterne(JournalComptable journal, String libelle, LocalDate datePiece,
                                            List<EcritureGrandLivre> lignes, User auteur) {
        return creerPieceInterne(journal, libelle, datePiece, lignes, auteur, true);
    }

    /**
     * Variante sans le verrou du rapprochement bancaire : réservée à la réévaluation de clôture des soldes en
     * devise, qui ajuste la contre-valeur en devise de base d'un compte de banque sans toucher à son solde en
     * devise, donc sans rien changer à un relevé déjà rapproché.
     */
    @Transactional
    public PieceComptable creerPieceInterne(JournalComptable journal, String libelle, LocalDate datePiece,
                                            List<EcritureGrandLivre> lignes, User auteur,
                                            boolean controlerRapprochement) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (EcritureGrandLivre l : lignes) {
            totalDebit = totalDebit.add(nz(l.getDebit()));
            totalCredit = totalCredit.add(nz(l.getCredit()));
        }
        // Garde-fou partie double : aucun flux interne (caisse, stock,
        // transport, sync) ne peut produire une pièce déséquilibrée.
        if (lignes.size() < 2 || totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                "Pièce interne non équilibrée refusée [journal=" + journal
                + ", libelle=" + libelle + ", débit=" + totalDebit + ", crédit=" + totalCredit + "]");
        }
        periodeService.verifierDateOuverte(datePiece);
        if (controlerRapprochement) {
            verifierNonRapproche(lignes, datePiece);
        }

        PieceComptable piece = PieceComptable.builder()
            .reference(referenceGenerator.pourPieceDefinitive(datePiece))
            .datePiece(datePiece)
            .journal(journal)
            .libelle(libelle)
            .statut(StatutPiece.COMPTABILISEE)
            .totalDebit(totalDebit)
            .totalCredit(totalCredit)
            .createdBy(auteur)
            .comptabiliseePar(auteur)
            .comptabiliseeLe(Instant.now())
            .build();

        // Pièce déjà COMPTABILISEE dès sa création : la saisie et la validation
        // sont le même instant, donc le taux du jour figé ici est bien celui de
        // l'opération. Un appelant ayant déjà résolu un taux (paiement d'une
        // note dans une devise différente) n'est jamais écrasé.
        // Le taux retenu est celui en vigueur A LA DATE DE LA PIECE, et non le
        // taux du jour : une piece antidatee doit porter la contre-valeur de
        // sa propre date.
        BigDecimal tauxDeLaPiece = conversionDevise.tauxALaDate(datePiece);
        for (EcritureGrandLivre l : lignes) {
            // Les flux automatiques imputent eux aussi sur des comptes de
            // saisie : sans ce controle, seule la saisie manuelle etait
            // protegee contre les comptes de regroupement et desactives.
            exigerCompteImputable(l.getCompte());
            l.setDateEcriture(datePiece);
            if (l.getTauxApplique() == null) {
                l.setTauxApplique(tauxDeLaPiece);
            }
            piece.addLigne(l);
        }

        return pieceRepository.save(piece);
    }

    /**
     * Crée une pièce interne au statut BROUILLON, à comptabiliser
     * manuellement (le DFIN la retrouve dans Pièces comptables) : à la
     * différence de {@link #creerPieceInterne}, la saisie et la validation
     * ne sont pas le même instant — l'appelant a besoin d'une revue humaine
     * avant que la pièce n'impacte le Grand Livre. Utilisée par le module
     * DRH pour la clôture de paie (une pièce par bulletin, jamais
     * auto-comptabilisée : la masse salariale reste un poste sensible).
     *
     * <p>Copie conforme de {@code creerPieceInterne} pour tout le reste
     * (garde-fou partie double, période ouverte, comptes imputables, taux
     * figé par ligne) — seul le statut de départ change.</p>
     */
    @Transactional
    public PieceComptable creerPieceInterneBrouillon(JournalComptable journal, String libelle, LocalDate datePiece,
                                                      List<EcritureGrandLivre> lignes, User auteur) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (EcritureGrandLivre l : lignes) {
            totalDebit = totalDebit.add(nz(l.getDebit()));
            totalCredit = totalCredit.add(nz(l.getCredit()));
        }
        if (lignes.size() < 2 || totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                "Pièce interne non équilibrée refusée [journal=" + journal
                + ", libelle=" + libelle + ", débit=" + totalDebit + ", crédit=" + totalCredit + "]");
        }
        periodeService.verifierDateOuverte(datePiece);

        PieceComptable piece = PieceComptable.builder()
            .reference(referenceGenerator.pourBrouillon())
            .datePiece(datePiece)
            .journal(journal)
            .libelle(libelle)
            .statut(StatutPiece.BROUILLON)
            .totalDebit(totalDebit)
            .totalCredit(totalCredit)
            .createdBy(auteur)
            .build();

        BigDecimal tauxDeLaPiece = conversionDevise.tauxALaDate(datePiece);
        for (EcritureGrandLivre l : lignes) {
            exigerCompteImputable(l.getCompte());
            l.setDateEcriture(datePiece);
            if (l.getTauxApplique() == null) {
                l.setTauxApplique(tauxDeLaPiece);
            }
            piece.addLigne(l);
        }

        return pieceRepository.save(piece);
    }

    // ---------------------------------------------------------------------
    // Grand livre par compte
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public GrandLivreCompteResponse grandLivreParCompte(String compteNumero, LocalDate du, LocalDate au) {
        if (!StringUtils.hasText(compteNumero)) {
            throw new IllegalArgumentException("Le numéro de compte est obligatoire");
        }
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        CompteOHADA compte = compteRepository.findByNumero(compteNumero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", compteNumero));

        Object[] reportRow = ecritureRepository.soldeAnterieur(compteNumero, debut);
        // Selon la version de Spring Data, un agrégat mono-ligne peut arriver
        // sous forme de tableau imbriqué : on normalise.
        if (reportRow != null && reportRow.length == 1 && reportRow[0] instanceof Object[] inner) {
            reportRow = inner;
        }
        BigDecimal reportDebit = reportRow != null && reportRow.length > 0 && reportRow[0] != null
            ? (BigDecimal) reportRow[0] : BigDecimal.ZERO;
        BigDecimal reportCredit = reportRow != null && reportRow.length > 1 && reportRow[1] != null
            ? (BigDecimal) reportRow[1] : BigDecimal.ZERO;
        BigDecimal reportSolde = soldeCompte(compte.getType(), reportDebit, reportCredit);

        List<EcritureGrandLivre> ecritures = ecritureRepository.grandLivreParCompte(compteNumero, debut, fin);
        BigDecimal soldeCourant = reportSolde;
        TypeCompte typeCompte = compte.getType();
        List<LigneGrandLivreResponse> lignes = new ArrayList<>();
        for (EcritureGrandLivre e : ecritures) {
            soldeCourant = soldeCourant.add(mouvementSolde(typeCompte, e.getDebit(), e.getCredit()));
            lignes.add(LigneGrandLivreResponse.of(e, soldeCourant));
        }

        return new GrandLivreCompteResponse(
            compte.getNumero(),
            compte.getLibelle(),
            debut,
            fin,
            reportDebit,
            reportCredit,
            reportSolde,
            lignes
        );
    }

    // ---------------------------------------------------------------------
    // Balance de vérification
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'CAISSIER', 'ADMIN')")
    @Transactional(readOnly = true)
    public BalanceVerificationResponse balanceVerification(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<LigneBalanceVerificationResponse> lignes = ecritureRepository.balanceVerification(debut, fin).stream()
            .map(row -> LigneBalanceVerificationResponse.of(
                (String) row[0],
                (String) row[1],
                (TypeCompte) row[2],
                (BigDecimal) row[3],
                (BigDecimal) row[4]))
            .toList();

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalSoldeDebiteur = BigDecimal.ZERO;
        BigDecimal totalSoldeCrediteur = BigDecimal.ZERO;
        for (LigneBalanceVerificationResponse l : lignes) {
            totalDebit = totalDebit.add(l.totalDebit());
            totalCredit = totalCredit.add(l.totalCredit());
            totalSoldeDebiteur = totalSoldeDebiteur.add(l.soldeDebiteur());
            totalSoldeCrediteur = totalSoldeCrediteur.add(l.soldeCrediteur());
        }

        return new BalanceVerificationResponse(
            debut,
            fin,
            lignes,
            totalDebit,
            totalCredit,
            totalSoldeDebiteur,
            totalSoldeCrediteur,
            totalDebit.compareTo(totalCredit) == 0
        );
    }

    // ---------------------------------------------------------------------
    // États financiers (livre-journal, compte de résultat, bilan)
    // ---------------------------------------------------------------------

    /** Livre-journal : pièces comptabilisées de la période, en ordre chronologique. */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public LivreJournalResponse livreJournal(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<PieceResponse> pieces = pieceRepository.livreJournal(debut, fin).stream()
            .map(PieceResponse::from)
            .toList();

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (PieceResponse p : pieces) {
            totalDebit = totalDebit.add(nz(p.totalDebit()));
            totalCredit = totalCredit.add(nz(p.totalCredit()));
        }
        return new LivreJournalResponse(debut, fin, pieces, totalDebit, totalCredit);
    }

    /**
     * Compte de résultat de la période : produits (classe 7, solde créditeur)
     * moins charges (classe 6, solde débiteur). Devise de base : CDF.
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'CAISSIER', 'ADMIN')")
    @Transactional(readOnly = true)
    public CompteResultatResponse compteResultat(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<EtatFinancierLigne> produits = new ArrayList<>();
        List<EtatFinancierLigne> charges = new ArrayList<>();
        BigDecimal totalProduits = BigDecimal.ZERO;
        BigDecimal totalCharges = BigDecimal.ZERO;

        for (Object[] row : ecritureRepository.mouvementsParCompte(debut, fin)) {
            String numero = (String) row[0];
            String libelle = (String) row[1];
            TypeCompte type = (TypeCompte) row[2];
            Integer classe = (Integer) row[3];
            BigDecimal debit = nz((BigDecimal) row[4]);
            BigDecimal credit = nz((BigDecimal) row[5]);

            if (type == TypeCompte.PRODUIT) {
                BigDecimal montant = credit.subtract(debit);
                if (montant.signum() != 0) {
                    produits.add(new EtatFinancierLigne(numero, libelle, classe, montant));
                    totalProduits = totalProduits.add(montant);
                }
            } else if (type == TypeCompte.CHARGE) {
                BigDecimal montant = debit.subtract(credit);
                if (montant.signum() != 0) {
                    charges.add(new EtatFinancierLigne(numero, libelle, classe, montant));
                    totalCharges = totalCharges.add(montant);
                }
            }
        }

        return new CompteResultatResponse(
            debut, fin, produits, charges,
            totalProduits, totalCharges,
            totalProduits.subtract(totalCharges));
    }

    /**
     * Bilan à une date : actif (solde débiteur des comptes ACTIF) face au
     * passif (solde créditeur des comptes PASSIF), le résultat net cumulé
     * étant intégré aux capitaux propres pour assurer l'équilibre.
     *
     * <p>Les comptes de la classe 9 (engagements hors bilan 90x, comptabilité analytique de gestion 92 à 99) n'ont pas
     * de type et ne relèvent pas du bilan : ils en sont écartés, comme du compte de résultat et de la clôture annuelle.
     * Ils figuraient à tort à l'actif (le type absent valait « ACTIF »).</p>
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public BilanResponse bilan(LocalDate au) {
        LocalDate fin = au != null ? au : LocalDate.now();

        List<EtatFinancierLigne> actifs = new ArrayList<>();
        List<EtatFinancierLigne> passifs = new ArrayList<>();
        BigDecimal totalActif = BigDecimal.ZERO;
        BigDecimal totalPassifHorsResultat = BigDecimal.ZERO;
        BigDecimal produitsCumules = BigDecimal.ZERO;
        BigDecimal chargesCumulees = BigDecimal.ZERO;

        for (Object[] row : ecritureRepository.cumulParCompte(fin)) {
            String numero = (String) row[0];
            String libelle = (String) row[1];
            TypeCompte type = (TypeCompte) row[2];
            Integer classe = (Integer) row[3];
            BigDecimal debit = nz((BigDecimal) row[4]);
            BigDecimal credit = nz((BigDecimal) row[5]);

            if (type == null || (classe != null && classe == CLASSE_HORS_BILAN)) {
                continue;
            }
            // Audit SYSCOHADA du 10/11/2026, C-16 : un compte de tiers (classe 4) ou de trésorerie (classe 5) se
            // présente du côté que lui donne le sens de son solde — un client créditeur est une dette, une banque
            // à découvert est une trésorerie-passif. Les dépréciations (49, 59) restent en moins de l'actif.
            if (reclasseSelonSolde(numero, classe)) {
                BigDecimal solde = debit.subtract(credit);
                if (type == TypeCompte.ACTIF && solde.signum() < 0) {
                    type = TypeCompte.PASSIF;
                } else if (type == TypeCompte.PASSIF && solde.signum() > 0) {
                    type = TypeCompte.ACTIF;
                }
            }

            switch (type) {
                case ACTIF -> {
                    BigDecimal montant = debit.subtract(credit);
                    if (montant.signum() != 0) {
                        actifs.add(new EtatFinancierLigne(numero, libelle, classe, montant));
                        totalActif = totalActif.add(montant);
                    }
                }
                case PASSIF -> {
                    BigDecimal montant = credit.subtract(debit);
                    if (montant.signum() != 0) {
                        passifs.add(new EtatFinancierLigne(numero, libelle, classe, montant));
                        totalPassifHorsResultat = totalPassifHorsResultat.add(montant);
                    }
                }
                case PRODUIT -> produitsCumules = produitsCumules.add(credit.subtract(debit));
                case CHARGE -> chargesCumulees = chargesCumulees.add(debit.subtract(credit));
            }
        }

        BigDecimal resultatNet = produitsCumules.subtract(chargesCumulees);
        BigDecimal totalPassif = totalPassifHorsResultat.add(resultatNet);

        return new BilanResponse(
            fin, actifs, passifs,
            totalActif, totalPassifHorsResultat, resultatNet, totalPassif,
            totalActif.compareTo(totalPassif) == 0);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Comptes de tiers et de trésorerie, hors dépréciations : présentés au bilan selon le sens de leur solde. */
    static boolean reclasseSelonSolde(String numero, Integer classe) {
        return classe != null && (classe == 4 || classe == 5) && numero != null
            && !numero.startsWith("49") && !numero.startsWith("59");
    }

    /**
     * Disponibilité (classe 5 hors dépréciations 59x) : caisse, banque, mobile money, virements internes. Un flux
     * interne qui laisse l'utilisateur choisir sa contrepartie (immobilisation, provision, entrée de stock) ne peut
     * pas en prendre une : ce serait déplacer de l'argent sans opération de trésorerie ni note de frais (audit
     * SYSCOHADA du 10/11/2026, C-08).
     */
    public static boolean estDisponibilite(String numero) {
        return estCompteTresorerie(numero) && !numero.startsWith("59");
    }

    /** Refuse une contrepartie choisie par l'utilisateur qui serait un compte de disponibilités (C-08). */
    public static void exigerContrepartieHorsTresorerie(String numero, String role) {
        if (estDisponibilite(numero)) {
            throw new IllegalArgumentException("Le compte " + numero + " est un compte de trésorerie : il ne peut pas "
                + "servir de " + role + ". Un paiement passe par une note de frais, exécutée par la caisse, la banque "
                + "ou le mobile money ; choisissez ici un compte de tiers (fournisseur, créance sur cession...).");
        }
    }

    /** Refuse toute imputation sur un compte de regroupement ou désactivé. */
    /**
     * Verrou du rapprochement bancaire : refuse une écriture sur un compte de banque ou de mobile money datée
     * d'un mois déjà rapproché et validé (voir {@code RapprochementService}).
     */
    private void verifierNonRapproche(List<EcritureGrandLivre> lignes, LocalDate date) {
        if (date == null) return;
        java.util.Set<Long> vus = new java.util.HashSet<>();
        for (EcritureGrandLivre l : lignes) {
            CompteOHADA c = l.getCompte();
            if (c == null || c.getId() == null || c.getNumero() == null || !c.getNumero().startsWith("5") || !vus.add(c.getId())) {
                continue;
            }
            releveRepository.validesDuCompte(c.getId()).stream().findFirst().ifPresent(dernier -> {
                LocalDate fin = java.time.YearMonth.of(dernier.getAnnee(), dernier.getMois()).atEndOfMonth();
                if (!date.isAfter(fin)) {
                    throw new TransitionInvalideException("Le compte " + c.getNumero() + " est rapproché et validé jusqu'au "
                        + fin + " : aucune écriture ne peut y être datée du " + date + ". Datez l'opération après cette "
                        + "date ou faites dé-valider le rapprochement bancaire.");
                }
            });
        }
    }

    private void exigerCompteImputable(CompteOHADA compte) {
        if (!compte.isActif()) {
            throw new IllegalArgumentException(
                "Le compte " + compte.getNumero() + " (" + compte.getLibelle()
                + ") est désactivé : aucune imputation possible.");
        }
        if (!compte.isImputable()) {
            throw new IllegalArgumentException(
                "Le compte " + compte.getNumero() + " (" + compte.getLibelle()
                + ") est un compte de regroupement : imputez sur un sous-compte de saisie.");
        }
    }

    private PieceComptable chargerAvecLignes(Long id) {
        return pieceRepository.findWithLignesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("PieceComptable", id));
    }

    /**
     * Racine des comptes de trésorerie : la classe 5 du SYSCOHADA (banques,
     * caisse, monnaie électronique, valeurs à encaisser, virements
     * internes...). C'est aussi la trésorerie des états financiers.
     */
    private static final String RACINE_COMPTES_TRESORERIE = "5";

    /** Journaux réservés aux opérations de trésorerie. */
    private static final Set<JournalComptable> JOURNAUX_TRESORERIE =
        EnumSet.of(JournalComptable.CAISSE, JournalComptable.BANQUE, JournalComptable.MOBILE_MONEY);

    public static boolean estCompteTresorerie(String numero) {
        return numero != null && numero.startsWith(RACINE_COMPTES_TRESORERIE);
    }

    /**
     * Refuse qu'une pièce saisie depuis l'écran Pièces comptables (création,
     * modification, comptabilisation) mouvemente la trésorerie ou porte un
     * journal de trésorerie. Un encaissement ou un décaissement passe par une
     * note de frais — vérifiée par le DFIN, approuvée par le DA, exécutée par
     * la caisse — ou par une opération de caisse, de banque ou de mobile
     * money, réservée au caissier. Sans cette règle, une pièce manuelle
     * (D charge / C 571) déplaçait de l'argent au grand livre en contournant
     * tout ce circuit : le DFIN, qui vérifie les notes, pouvait s'en passer.
     *
     * <p>Aucune exception, pas même pour un solde d'ouverture : la reprise
     * de la trésorerie est faite, et les exercices suivants reportent leurs
     * soldes d'eux-mêmes. Les flux internes (caisse, ventes, stock, paie...)
     * passent par {@link #creerPieceInterne} et ne sont pas concernés.</p>
     */
    private void exigerHorsTresorerie(JournalComptable journal, List<String> numerosComptes) {
        if (JOURNAUX_TRESORERIE.contains(journal)) {
            throw new IllegalArgumentException(
                "Le journal " + journal + " est réservé aux opérations de trésorerie, qui ne se saisissent pas "
                + "ici : un encaissement ou un décaissement passe par une note de frais.");
        }
        List<String> tresorerie = numerosComptes.stream()
            .filter(ComptabiliteService::estCompteTresorerie)
            .distinct()
            .toList();
        if (!tresorerie.isEmpty()) {
            throw new IllegalArgumentException(
                "Une pièce saisie ici ne peut pas mouvementer un compte de trésorerie (" + String.join(", ", tresorerie)
                + ") : un encaissement ou un décaissement passe par une note de frais, vérifiée par le DFIN, "
                + "approuvée par le DA et exécutée par la caisse.");
        }
    }

    /**
     * Solde d'ouverture : la case « reprise des à-nouveaux » cochée par le comptable, ou la règle du 13/11/2026 — une
     * pièce datée du 1er janvier qui ne porte que des comptes de bilan (voir {@link SoldeOuverture}).
     */
    private static boolean estSoldeOuverture(PieceCreateRequest req, LocalDate datePiece) {
        return req.soldeOuverture() || SoldeOuverture.concerne(datePiece,
            req.lignes().stream().map(LigneEcritureRequest::compteNumero).toList());
    }

    private void validerLignes(List<LigneEcritureRequest> lignes) {
        if (lignes == null || lignes.size() < 2) {
            throw new IllegalArgumentException("Une pièce comptable doit contenir au moins 2 lignes");
        }
        for (LigneEcritureRequest l : lignes) {
            BigDecimal d = nz(l.debit());
            BigDecimal c = nz(l.credit());
            if (d.signum() == 0 && c.signum() == 0) {
                throw new IllegalArgumentException("Chaque ligne doit avoir un débit ou un crédit non nul");
            }
            if (d.signum() > 0 && c.signum() > 0) {
                throw new IllegalArgumentException("Une ligne ne peut pas avoir à la fois un débit et un crédit");
            }
            if (!StringUtils.hasText(l.compteNumero())) {
                throw new IllegalArgumentException("Le numéro de compte est obligatoire pour chaque ligne");
            }
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Solde progressif : débit - crédit (convention balance de vérification). */
    private static BigDecimal soldeCompte(TypeCompte type, BigDecimal debit, BigDecimal credit) {
        BigDecimal d = nz(debit);
        BigDecimal c = nz(credit);
        return switch (type == null ? TypeCompte.ACTIF : type) {
            case PRODUIT, PASSIF -> c.subtract(d);
            case CHARGE, ACTIF -> d.subtract(c);
        };
    }

    private static BigDecimal mouvementSolde(TypeCompte type, BigDecimal debit, BigDecimal credit) {
        return soldeCompte(type, debit, credit);
    }
}

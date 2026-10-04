package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.TypeCompte;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Module comptabilité générale : pièces comptables équilibrées, grand livre par compte,
 * balance de vérification (inspiré ERPNext Journal Entry).
 */
@Service
@RequiredArgsConstructor
public class ComptabiliteService {

    private static final Logger log = LoggerFactory.getLogger(ComptabiliteService.class);

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

    // ---------------------------------------------------------------------
    // Pièces comptables
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
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
        PieceComptable piece = PieceComptable.builder()
            .reference(referenceGenerator.pourPiece())
            .datePiece(datePiece)
            .journal(req.journal())
            .libelle(req.libelle())
            .statut(StatutPiece.BROUILLON)
            .soldeOuverture(req.soldeOuverture())
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
        log.info("Pièce comptable créée [ref={}, par={}]", saved.getReference(), auteur.getEmail());
        return PieceResponse.from(saved);
    }

    /**
     * Modifie une pièce encore au BROUILLON (façon Sage : le brouillard reste
     * corrigeable tant qu'il n'est pas validé). Une pièce comptabilisée est
     * immuable : seule l'extourne est possible.
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
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

        piece.setDatePiece(datePiece);
        piece.setJournal(req.journal());
        piece.setLibelle(req.libelle());
        piece.setSoldeOuverture(req.soldeOuverture());
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
    public PieceResponse basculerSoldeOuverture(Long id, boolean soldeOuverture) {
        PieceComptable piece = chargerAvecLignes(id);
        piece.setSoldeOuverture(soldeOuverture);
        log.info("Marqueur solde d'ouverture {} [ref={}]", soldeOuverture ? "active" : "desactive", piece.getReference());
        return PieceResponse.from(piece);
    }

    /** Supprime une pièce encore au BROUILLON (jamais une pièce comptabilisée). */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public void supprimerPiece(Long id) {
        PieceComptable piece = chargerAvecLignes(id);
        if (piece.getStatut() != StatutPiece.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une pièce BROUILLON peut être supprimée (état actuel : " + piece.getStatut()
                + "). Une pièce comptabilisée doit être extournée.");
        }
        pieceRepository.delete(piece);
        log.info("Pièce brouillon supprimée [ref={}]", piece.getReference());
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<PieceResponse> listerPieces() {
        // L'écran ne propose la comptabilisation et l'extourne que là où
        // comptabiliser() et annuler() les accepteront.
        java.util.Set<Long> liees = new java.util.HashSet<>(pieceRepository.idsPiecesLieesAUneOperation());
        java.util.Set<Long> tresorerie = new java.util.HashSet<>(pieceRepository.idsPiecesTresorerie());
        return pieceRepository.findAllWithCreatedByOrderByDatePieceDesc().stream()
            .map(p -> {
                boolean touche = tresorerie.contains(p.getId());
                return PieceResponse.from(p, false,
                    p.getStatut() == StatutPiece.COMPTABILISEE && !liees.contains(p.getId()) && !touche,
                    p.getStatut() == StatutPiece.BROUILLON && !touche && !JOURNAUX_TRESORERIE.contains(p.getJournal()),
                    touche);
            })
            .toList();
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public PieceResponse consulterPiece(Long id) {
        return PieceResponse.from(chargerAvecLignes(id));
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
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
        // Le brouillon peut avoir été saisi puis validé plusieurs jours après :
        // c'est le taux du jour de la VALIDATION (pas de la saisie) qui fige
        // durablement chaque écriture en FC pour son affichage ultérieur en USD.
        BigDecimal tauxDuJour = conversionDevise.tauxCourant();
        for (EcritureGrandLivre ligne : piece.getLignes()) {
            if (ligne.getTauxApplique() == null) {
                ligne.setTauxApplique(tauxDuJour);
            }
        }
        piece.setStatut(StatutPiece.COMPTABILISEE);
        log.info("Pièce comptabilisée [ref={}]", piece.getReference());
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
     * <p>Refuse enfin toute pièce qui mouvemente un compte de trésorerie,
     * même sans opération derrière elle (journal historique importé) : voir
     * {@link #exigerHorsTresorerie}.</p>
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'ADMIN')")
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
        return PieceResponse.from(extournerInterne(originale, currentUser.requireUser()));
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
            .reference(referenceGenerator.pourPiece())
            .datePiece(dateExtourne)
            .journal(originale.getJournal())
            .libelle(libelleExtourne)
            .statut(StatutPiece.COMPTABILISEE)
            .totalDebit(originale.getTotalCredit())
            .totalCredit(originale.getTotalDebit())
            .createdBy(auteur)
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

        originale.setStatut(StatutPiece.ANNULEE);
        pieceRepository.save(extourne);
        log.info("Pièce {} annulée par extourne [ref={}]", originale.getReference(), extourne.getReference());
        return originale;
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

        PieceComptable piece = PieceComptable.builder()
            .reference(referenceGenerator.pourPiece())
            .datePiece(datePiece)
            .journal(journal)
            .libelle(libelle)
            .statut(StatutPiece.COMPTABILISEE)
            .totalDebit(totalDebit)
            .totalCredit(totalCredit)
            .createdBy(auteur)
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
            .reference(referenceGenerator.pourPiece())
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

            switch (type == null ? TypeCompte.ACTIF : type) {
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

    /** Refuse toute imputation sur un compte de regroupement ou désactivé. */
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

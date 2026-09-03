package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.*;
import com.mbsc.finapp.dto.patrimoine.*;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.*;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module Patrimoine : registre des biens immobilises, plan d'amortissement
 * lineaire et ecritures OHADA associees (acquisition, dotations, sortie).
 *
 * <p><b>Amortissement.</b> Lineaire mensuel, avec prorata temporis a la date
 * de mise en service : le plan complet est calcule et stocke des la creation
 * du bien, ce qui le rend consultable en previsionnel. La derniere echeance
 * absorbe le residu d'arrondi, de sorte que le cumul retombe exactement sur la
 * base amortissable (valeur d'acquisition moins valeur residuelle) — au
 * centime pres, sans derive sur plusieurs annees.</p>
 *
 * <p><b>Comptabilisation volontaire.</b> Les dotations ne sont pas passees par
 * une tache planifiee mais par une action explicite. Une ecriture comptable
 * qui se declenche seule, sur une periode potentiellement close, est
 * difficile a auditer et impossible a arreter ; ici le gestionnaire choisit
 * la date d'arret et voit exactement ce qui a ete passe. L'idempotence est
 * garantie par la contrainte d'unicite (bien, periode) doublee du drapeau
 * {@code comptabilise} : relancer la campagne ne repasse jamais une periode
 * deja ecrite.</p>
 *
 * <p><b>Comptes.</b> Tous les comptes sont resolus par numero et verifies
 * imputables par {@code ComptabiliteService}. Attention : les comptes de
 * regroupement du referentiel (681, 81, 82, 811, 821) ne sont PAS imputables,
 * et 6811 a ete desactive — ce sont 6812/6813, 812 et 822 qu'il faut utiliser.
 * Les valeurs par defaut de {@link CategorieImmobilisation} respectent cette
 * contrainte.</p>
 */
@Service
@RequiredArgsConstructor
public class PatrimoineService {

    private static final Logger log = LoggerFactory.getLogger(PatrimoineService.class);

    /** VNC et produit de cession des immobilisations corporelles (imputables). */
    private static final String COMPTE_VNC_CESSION = "812";
    private static final String COMPTE_PRODUIT_CESSION = "822";
    /** Contrepartie d'acquisition par defaut : fournisseurs d'investissements. */
    private static final String COMPTE_FOURNISSEUR_INVEST = "4812";

    private final ImmobilisationRepository immobilisationRepository;
    private final LigneAmortissementRepository ligneRepository;
    private final CompteOHADARepository compteRepository;
    private final UserRepository userRepository;
    private final ComptabiliteService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;

    // -----------------------------------------------------------------
    // Consultation
    // -----------------------------------------------------------------

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ImmobilisationResponse> lister() {
        return immobilisationRepository.findAllWithComptes().stream()
            .map(ImmobilisationResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public ImmobilisationResponse consulter(Long id) {
        return ImmobilisationResponse.from(charger(id));
    }

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<LigneAmortissementResponse> planAmortissement(Long id) {
        return charger(id).getPlanAmortissement().stream()
            .map(LigneAmortissementResponse::from)
            .toList();
    }

    // -----------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'ADMIN')")
    @Transactional
    public ImmobilisationResponse creer(ImmobilisationRequest req) {
        User auteur = currentUser.requireUser();
        valider(req);

        Immobilisation immo = Immobilisation.builder()
            .reference(referenceGenerator.pourImmobilisation())
            .libelle(req.libelle())
            .categorie(req.categorie())
            .description(req.description())
            .compteImmobilisation(resoudreCompte(
                req.compteImmobilisationNumero(), req.categorie().compteImmobilisationParDefaut(), "immobilisation"))
            .compteAmortissement(resoudreCompte(
                req.compteAmortissementNumero(), req.categorie().compteAmortissementParDefaut(), "amortissement"))
            .compteDotation(resoudreCompte(
                req.compteDotationNumero(), req.categorie().compteDotationParDefaut(), "dotation"))
            .dateAcquisition(req.dateAcquisition())
            .dateMiseService(req.dateMiseService())
            .valeurAcquisition(req.valeurAcquisition())
            .valeurResiduelle(req.valeurResiduelle() == null ? BigDecimal.ZERO : req.valeurResiduelle())
            .dureeMois(req.dureeMois())
            .modeAmortissement(req.modeAmortissement() == null
                ? ModeAmortissement.LINEAIRE : req.modeAmortissement())
            .statut(StatutImmobilisation.EN_SERVICE)
            .localisation(req.localisation())
            .responsable(resoudreResponsable(req.responsableId()))
            .fournisseur(req.fournisseur())
            .numeroSerie(req.numeroSerie())
            .createdBy(auteur)
            .build();

        construirePlan(immo);

        if (req.comptabiliserAcquisition()) {
            immo.setPieceAcquisition(ecrireAcquisition(immo, req.compteContrepartieNumero(), auteur));
        }

        Immobilisation saved = immobilisationRepository.save(immo);
        log.info("Immobilisation creee [ref={}, valeur={}, duree={} mois]",
            saved.getReference(), saved.getValeurAcquisition(), saved.getDureeMois());
        return ImmobilisationResponse.from(saved);
    }

    private void valider(ImmobilisationRequest req) {
        BigDecimal residuelle = req.valeurResiduelle() == null ? BigDecimal.ZERO : req.valeurResiduelle();
        if (residuelle.compareTo(req.valeurAcquisition()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La valeur residuelle (" + residuelle + ") doit etre inferieure a la valeur d'acquisition ("
                + req.valeurAcquisition() + ") : sinon il n'y a rien a amortir.");
        }
        if (req.dateMiseService().isBefore(req.dateAcquisition())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La mise en service (" + req.dateMiseService() + ") ne peut pas preceder l'acquisition ("
                + req.dateAcquisition() + ").");
        }
    }

    // -----------------------------------------------------------------
    // Moteur d'amortissement lineaire
    // -----------------------------------------------------------------

    /**
     * Construit le plan mensuel complet.
     *
     * <p><b>Lineaire</b> : la dotation de chaque mois est la base divisee par
     * la duree.</p>
     *
     * <p><b>Degressif</b> : la dotation se calcule sur la valeur restant a
     * amortir, au taux lineaire majore du coefficient fiscal
     * ({@link ModeAmortissement#coefficient}). Des que l'annuite lineaire du
     * temps restant depasse l'annuite degressive, on bascule sur le lineaire
     * pour le solde — c'est la regle du bareme, sans laquelle un degressif pur
     * n'atteindrait jamais zero.</p>
     *
     * <p>Dans les deux cas, la derniere echeance recoit le residu pour que le
     * cumul final egale exactement la base amortissable, au centime pres.</p>
     */
    private void construirePlan(Immobilisation immo) {
        BigDecimal base = immo.baseAmortissable();
        int duree = immo.getDureeMois();
        boolean degressif = immo.getModeAmortissement() == ModeAmortissement.DEGRESSIF;
        BigDecimal tauxMensuelDegressif = degressif
            ? BigDecimal.valueOf(ModeAmortissement.coefficient(duree))
                .divide(BigDecimal.valueOf(duree), 10, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal mensualiteLineaire = base.divide(BigDecimal.valueOf(duree), 2, RoundingMode.HALF_UP);

        LocalDate periode = immo.getDateMiseService().withDayOfMonth(1);
        BigDecimal cumul = BigDecimal.ZERO;

        for (int i = 0; i < duree; i++) {
            BigDecimal dotation;
            if (i == duree - 1) {
                dotation = base.subtract(cumul);          // solde exact
            } else if (degressif) {
                BigDecimal restant = base.subtract(cumul);
                BigDecimal parDegressif = restant.multiply(tauxMensuelDegressif).setScale(2, RoundingMode.HALF_UP);
                // Annuite lineaire recalculee sur le temps restant : c'est elle
                // qui prend le relais en fin de plan.
                BigDecimal parLineaire = restant.divide(BigDecimal.valueOf(duree - i), 2, RoundingMode.HALF_UP);
                dotation = parDegressif.max(parLineaire);
            } else {
                dotation = mensualiteLineaire;
            }
            // Ne jamais amortir au-dela de la base, quel que soit l'arrondi.
            dotation = dotation.min(base.subtract(cumul));
            cumul = cumul.add(dotation);
            immo.addLigne(LigneAmortissement.builder()
                .periode(periode)
                .baseAmortissable(base)
                .dotation(dotation)
                .cumul(cumul)
                .valeurNette(immo.getValeurAcquisition().subtract(cumul))
                .comptabilise(false)
                .build());
            periode = periode.plusMonths(1);
        }
    }

    // -----------------------------------------------------------------
    // Comptabilisation des dotations
    // -----------------------------------------------------------------

    /**
     * Passe toutes les dotations echues jusqu'a la date demandee, une piece
     * par bien (lisible dans le grand livre : une ligne de dotation, une ligne
     * d'amortissement cumule).
     */
    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'DFIN', 'ADMIN')")
    @Transactional
    public DotationsResponse comptabiliserDotations(LocalDate jusqua) {
        User auteur = currentUser.requireUser();
        List<LigneAmortissement> aPasser = ligneRepository.aComptabiliser(jusqua);

        Map<Immobilisation, List<LigneAmortissement>> parBien = new LinkedHashMap<>();
        for (LigneAmortissement l : aPasser) {
            parBien.computeIfAbsent(l.getImmobilisation(), k -> new ArrayList<>()).add(l);
        }

        int comptabilisees = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> pieces = new ArrayList<>();
        List<String> ignorees = new ArrayList<>();

        for (var entree : parBien.entrySet()) {
            Immobilisation immo = entree.getKey();
            List<LigneAmortissement> lignes = entree.getValue();

            if (immo.getCompteDotation() == null || immo.getCompteAmortissement() == null) {
                ignorees.add(immo.getReference() + " : compte de dotation ou d'amortissement non defini");
                continue;
            }

            BigDecimal montant = lignes.stream()
                .map(LigneAmortissement::getDotation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (montant.signum() <= 0) {
                continue;
            }

            LocalDate datePiece = lignes.get(lignes.size() - 1).getPeriode()
                .withDayOfMonth(1).plusMonths(1).minusDays(1);   // fin du mois amorti
            String libelle = "Dotation amortissement " + immo.getReference() + " - " + immo.getLibelle();

            List<EcritureGrandLivre> ecritures = List.of(
                ecriture(immo.getCompteDotation(), montant, BigDecimal.ZERO, libelle, datePiece),
                ecriture(immo.getCompteAmortissement(), BigDecimal.ZERO, montant, libelle, datePiece));

            try {
                PieceComptable piece = comptabilite.creerPieceInterne(
                    JournalComptable.OPERATIONS_DIVERSES, libelle, datePiece, ecritures, auteur);
                Instant maintenant = Instant.now();
                for (LigneAmortissement l : lignes) {
                    l.setComptabilise(true);
                    l.setPiece(piece);
                    l.setDateComptabilisation(maintenant);
                }
                comptabilisees += lignes.size();
                total = total.add(montant);
                pieces.add(piece.getReference());
            } catch (RuntimeException e) {
                // Periode close, compte devenu non imputable : on ecarte ce
                // bien et on poursuit, plutot que d'annuler toute la campagne.
                ignorees.add(immo.getReference() + " : " + e.getMessage());
            }
        }

        log.info("Dotations comptabilisees jusqu'au {} [lignes={}, total={}, ignorees={}]",
            jusqua, comptabilisees, total, ignorees.size());
        return new DotationsResponse(comptabilisees, total, pieces, ignorees);
    }

    // -----------------------------------------------------------------
    // Acquisition et sortie
    // -----------------------------------------------------------------

    /** Debit du compte d'immobilisation, credit de la contrepartie choisie. */
    private PieceComptable ecrireAcquisition(Immobilisation immo, String contrepartieNumero, User auteur) {
        CompteOHADA contrepartie = resoudreCompte(
            contrepartieNumero, COMPTE_FOURNISSEUR_INVEST, "contrepartie d'acquisition");
        String libelle = "Acquisition " + immo.getReference() + " - " + immo.getLibelle();
        List<EcritureGrandLivre> ecritures = List.of(
            ecriture(immo.getCompteImmobilisation(), immo.getValeurAcquisition(), BigDecimal.ZERO,
                libelle, immo.getDateAcquisition()),
            ecriture(contrepartie, BigDecimal.ZERO, immo.getValeurAcquisition(),
                libelle, immo.getDateAcquisition()));
        return comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, immo.getDateAcquisition(), ecritures, auteur);
    }

    /**
     * Sort un bien du patrimoine (cession ou rebut).
     *
     * <p>Sequence OHADA : on solde l'amortissement cumule et la valeur brute,
     * la valeur nette restante part en charge (812) ; si le bien est vendu, le
     * prix est encaisse en contrepartie d'un produit de cession (822). Les
     * deux volets forment une seule piece equilibree.</p>
     */
    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'ADMIN')")
    @Transactional
    public ImmobilisationResponse sortir(Long id, SortieRequest req) {
        User auteur = currentUser.requireUser();
        Immobilisation immo = charger(id);

        if (immo.estSorti()) {
            throw new TransitionInvalideException(
                "Le bien " + immo.getReference() + " est deja sorti du patrimoine (" + immo.getStatut() + ")");
        }
        if (req.dateSortie().isBefore(immo.getDateAcquisition())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La date de sortie ne peut pas preceder l'acquisition.");
        }

        BigDecimal prix = req.valeurCession() == null ? BigDecimal.ZERO : req.valeurCession();
        BigDecimal cumul = immo.cumulComptabilise();
        BigDecimal vnc = immo.getValeurAcquisition().subtract(cumul);

        String libelle = (prix.signum() > 0 ? "Cession " : "Mise au rebut ")
            + immo.getReference() + " - " + immo.getLibelle();

        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        // Solde de l'amortissement deja pratique.
        if (cumul.signum() > 0 && immo.getCompteAmortissement() != null) {
            ecritures.add(ecriture(immo.getCompteAmortissement(), cumul, BigDecimal.ZERO, libelle, req.dateSortie()));
        }
        // Valeur nette comptable sortie en charge.
        if (vnc.signum() > 0) {
            ecritures.add(ecriture(exigerCompte(COMPTE_VNC_CESSION), vnc, BigDecimal.ZERO, libelle, req.dateSortie()));
        }
        // Sortie de la valeur brute.
        ecritures.add(ecriture(immo.getCompteImmobilisation(), BigDecimal.ZERO,
            immo.getValeurAcquisition(), libelle, req.dateSortie()));
        // Volet vente, le cas echeant.
        if (prix.signum() > 0) {
            CompteOHADA contrepartie = resoudreCompte(
                req.compteContrepartieNumero(), COMPTE_FOURNISSEUR_INVEST, "contrepartie de cession");
            ecritures.add(ecriture(contrepartie, prix, BigDecimal.ZERO, libelle, req.dateSortie()));
            ecritures.add(ecriture(exigerCompte(COMPTE_PRODUIT_CESSION), BigDecimal.ZERO, prix, libelle, req.dateSortie()));
        }

        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, req.dateSortie(), ecritures, auteur);

        immo.setStatut(prix.signum() > 0 ? StatutImmobilisation.CEDE : StatutImmobilisation.REBUT);
        immo.setDateSortie(req.dateSortie());
        immo.setValeurCession(prix);
        immo.setPieceSortie(piece);

        log.info("Immobilisation sortie [ref={}, statut={}, vnc={}, prix={}, piece={}]",
            immo.getReference(), immo.getStatut(), vnc, prix, piece.getReference());
        return ImmobilisationResponse.from(immo);
    }

    // -----------------------------------------------------------------
    // Utilitaires
    // -----------------------------------------------------------------

    private Immobilisation charger(Long id) {
        return immobilisationRepository.findWithPlanById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Immobilisation", id));
    }

    private EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                         String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    private CompteOHADA resoudreCompte(String demande, String defaut, String role) {
        String numero = StringUtils.hasText(demande) ? demande.trim() : defaut;
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Compte " + role + " introuvable au plan comptable : " + numero));
    }

    private CompteOHADA exigerCompte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new IllegalStateException(
                "Compte " + numero + " absent du plan comptable"));
    }

    private User resoudreResponsable(Long id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Utilisateur responsable introuvable : " + id));
    }
}

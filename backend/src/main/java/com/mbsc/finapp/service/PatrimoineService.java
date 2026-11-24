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

    /** VNC et produit de cession hors activites ordinaires (imputables). */
    private static final String COMPTE_VNC_CESSION = "812";
    private static final String COMPTE_PRODUIT_CESSION = "822";
    /** VNC et produit de cession courante (renouvellement ordinaire des equipements, C-18). */
    private static final String COMPTE_VNC_CESSION_COURANTE = "6542";
    private static final String COMPTE_PRODUIT_CESSION_COURANTE = "7542";
    /** Contrepartie d'acquisition par defaut : fournisseurs d'investissements. */
    private static final String COMPTE_FOURNISSEUR_INVEST = "4812";
    /** Contrepartie de cession par defaut : creance sur cession d'immobilisation (C-18). */
    private static final String COMPTE_CREANCE_CESSION = "4851";

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

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'LOGISTIQUE', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ImmobilisationResponse> lister() {
        return immobilisationRepository.findAllWithComptes().stream()
            .map(ImmobilisationResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'LOGISTIQUE', 'ADMIN')")
    @Transactional(readOnly = true)
    public ImmobilisationResponse consulter(Long id) {
        return ImmobilisationResponse.from(charger(id));
    }

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'LOGISTIQUE', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<LigneAmortissementResponse> planAmortissement(Long id) {
        return charger(id).getPlanAmortissement().stream()
            .map(LigneAmortissementResponse::from)
            .toList();
    }

    // -----------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'LOGISTIQUE', 'ADMIN')")
    @Transactional
    public ImmobilisationResponse creer(ImmobilisationRequest req) {
        User auteur = currentUser.requireUser();
        valider(req);
        Immobilisation parent = null;
        if (req.parentId() != null) {
            parent = charger(req.parentId());
            if (parent.estSorti()) {
                throw new TransitionInvalideException("Le bien " + parent.getReference()
                    + " est sorti du patrimoine : on ne lui ajoute plus de composant.");
            }
            if (parent.getParent() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le bien " + parent.getReference()
                    + " est lui-même un composant : rattachez ce composant au bien principal.");
            }
        }

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
            .parent(parent)
            .createdBy(auteur)
            .build();

        construirePlan(immo);

        if (req.comptabiliserAcquisition()) {
            immo.setPieceAcquisition(ecrireAcquisition(immo, req.compteContrepartieNumero(), auteur));
        }

        Immobilisation saved = immobilisationRepository.save(immo);
        log.info("Immobilisation creee [ref={}, valeur={}, duree={} mois]",
            saved.getReference(), saved.getValeurAcquisition(), saved.getDureeMois());
        // Bien mis en service avant le mois en cours : ses dotations des mois déjà écoulés se passent tout de suite,
        // sans attendre la tâche quotidienne (amortissements cumulés et valeur nette à jour dès la création).
        LocalDate echu = finDuMoisEcoule(LocalDate.now());
        if (!saved.getDateMiseService().isAfter(echu)) {
            comptabiliserDotationsInterne(echu, auteur, saved.getId());
        }
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
    // Modification de la fiche (responsable logistique, 19/11/2026)
    // -----------------------------------------------------------------

    @PreAuthorize("hasAnyRole('GEST_PATRIMOINE', 'LOGISTIQUE', 'ADMIN')")
    @Transactional
    public ImmobilisationResponse modifierFiche(Long id, com.mbsc.finapp.dto.patrimoine.FicheImmobilisationRequest req) {
        Immobilisation immo = charger(id);
        if (immo.estSorti()) {
            throw new TransitionInvalideException("Le bien " + immo.getReference() + " est sorti du patrimoine : sa fiche est close.");
        }
        boolean dotationsPassees = immo.getPlanAmortissement().stream().anyMatch(LigneAmortissement::isComptabilise);
        boolean acquisitionPassee = immo.getPieceAcquisition() != null;
        StringBuilder changements = new StringBuilder();

        immo.setLibelle(req.libelle().strip());
        immo.setDescription(req.description());
        immo.setLocalisation(req.localisation());
        immo.setFournisseur(req.fournisseur());
        immo.setNumeroSerie(req.numeroSerie());
        if (req.categorie() != null && req.categorie() != immo.getCategorie()) {
            changements.append("catégorie ").append(immo.getCategorie()).append(" → ").append(req.categorie()).append(" ; ");
            immo.setCategorie(req.categorie());
        }

        // Valeur, dates et compte d'immobilisation : figés dès que l'acquisition ou une dotation est comptabilisée
        // (une correction passe alors par une sortie ou une écriture de régularisation).
        boolean valeurChange = req.valeurAcquisition() != null && req.valeurAcquisition().compareTo(immo.getValeurAcquisition()) != 0;
        boolean dateChange = (req.dateMiseService() != null && !req.dateMiseService().equals(immo.getDateMiseService()))
            || (req.dateAcquisition() != null && !req.dateAcquisition().equals(immo.getDateAcquisition()));
        boolean compteImmoChange = req.compteImmobilisationNumero() != null && immo.getCompteImmobilisation() != null
            && !req.compteImmobilisationNumero().equals(immo.getCompteImmobilisation().getNumero());
        if ((valeurChange || dateChange || compteImmoChange) && (acquisitionPassee || dotationsPassees)) {
            throw new TransitionInvalideException("La valeur, les dates et le compte d'immobilisation de " + immo.getReference()
                + " sont déjà comptabilisés : ils ne se modifient plus (sortie du bien ou écriture de régularisation).");
        }
        if (valeurChange) {
            changements.append("valeur ").append(immo.getValeurAcquisition()).append(" → ").append(req.valeurAcquisition()).append(" ; ");
            immo.setValeurAcquisition(req.valeurAcquisition());
        }
        if (req.dateAcquisition() != null) immo.setDateAcquisition(req.dateAcquisition());
        if (req.dateMiseService() != null) immo.setDateMiseService(req.dateMiseService());
        if (immo.getDateMiseService().isBefore(immo.getDateAcquisition())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La mise en service ne peut pas précéder l'acquisition.");
        }
        if (compteImmoChange) {
            immo.setCompteImmobilisation(resoudreCompte(req.compteImmobilisationNumero(), null, "immobilisation"));
        }
        if (req.compteAmortissementNumero() != null && !dotationsPassees) {
            immo.setCompteAmortissement(resoudreCompte(req.compteAmortissementNumero(), null, "amortissement"));
        }
        if (req.compteDotationNumero() != null) {
            immo.setCompteDotation(resoudreCompte(req.compteDotationNumero(), null, "dotation"));
        }

        // Plan : durée, mode, valeur résiduelle.
        int duree = req.dureeMois() == null ? immo.getDureeMois() : req.dureeMois();
        ModeAmortissement mode = req.modeAmortissement() == null ? immo.getModeAmortissement() : req.modeAmortissement();
        BigDecimal residuelle = req.valeurResiduelle() == null ? immo.getValeurResiduelle() : req.valeurResiduelle();
        boolean planChange = valeurChange || dateChange || duree != immo.getDureeMois() || mode != immo.getModeAmortissement()
            || residuelle.compareTo(immo.getValeurResiduelle()) != 0;
        if (residuelle.compareTo(immo.getValeurAcquisition()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La valeur résiduelle doit rester inférieure à la valeur d'acquisition.");
        }
        if (planChange) {
            changements.append("plan : ").append(immo.getDureeMois()).append(" → ").append(duree).append(" mois, ")
                .append(mode).append(", résiduelle ").append(residuelle).append(" ; ");
            immo.setDureeMois(duree);
            immo.setModeAmortissement(mode);
            immo.setValeurResiduelle(residuelle);
            if (dotationsPassees) {
                replanifierSuite(immo);
            } else {
                immo.getPlanAmortissement().clear();
                immobilisationRepository.flush();
                construirePlan(immo);
            }
        }
        com.mbsc.finapp.audit.AuditService.detailler("Fiche " + immo.getReference() + " modifiée : "
            + (changements.length() == 0 ? "informations" : changements) + (req.motif() == null ? "" : " Motif : " + req.motif()));
        log.info("Fiche d'immobilisation modifiee [ref={}, {}]", immo.getReference(), changements);
        return ImmobilisationResponse.from(immobilisationRepository.save(immo));
    }

    /**
     * Changement d'estimation après des dotations comptabilisées : les échéances passées restent ; le reste à amortir
     * (base moins cumul comptabilisé) est réparti, en linéaire, sur les mois restants de la nouvelle durée — jamais moins
     * d'un mois. Le mode dégressif ne se reprend pas en cours de plan (il resterait sinon un résidu fiscal incohérent).
     */
    private void replanifierSuite(Immobilisation immo) {
        List<LigneAmortissement> passees = immo.getPlanAmortissement().stream().filter(LigneAmortissement::isComptabilise).toList();
        immo.getPlanAmortissement().removeIf(l -> !l.isComptabilise());
        immobilisationRepository.flush();
        BigDecimal base = immo.baseAmortissable();
        BigDecimal cumul = immo.cumulComptabilise();
        BigDecimal reste = base.subtract(cumul);
        if (reste.signum() <= 0) return;
        int restants = Math.max(1, immo.getDureeMois() - passees.size());
        LocalDate periode = passees.get(passees.size() - 1).getPeriode().plusMonths(1);
        BigDecimal mensualite = reste.divide(BigDecimal.valueOf(restants), 2, RoundingMode.HALF_UP);
        for (int i = 0; i < restants; i++) {
            BigDecimal dotation = i == restants - 1 ? base.subtract(cumul) : mensualite.min(base.subtract(cumul));
            cumul = cumul.add(dotation);
            immo.addLigne(LigneAmortissement.builder()
                .periode(periode).baseAmortissable(base).dotation(dotation).cumul(cumul)
                .valeurNette(immo.getValeurAcquisition().subtract(cumul)).comptabilise(false).build());
            periode = periode.plusMonths(1);
        }
    }

    /** Lie la fiche au code d'inventaire collé sur le bien (V153). */
    @Transactional
    public void lierCodeInventaireInterne(Long immobilisationId, String code) {
        Immobilisation immo = charger(immobilisationId);
        immo.setCodeInventaire(code);
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

        // Lineaire : prorata au jour pres de la mise en service (audit SYSCOHADA du 10/11/2026, C-18). Le premier
        // mois ne compte que les jours d'utilisation ; le reliquat passe sur un mois de plus en fin de plan. Le
        // degressif garde la regle fiscale du mois entier.
        int joursDuMois = immo.getDateMiseService().lengthOfMonth();
        int joursUtilises = joursDuMois - immo.getDateMiseService().getDayOfMonth() + 1;
        boolean prorata = !degressif && joursUtilises < joursDuMois;
        BigDecimal premiereMensualite = prorata
            ? mensualiteLineaire.multiply(BigDecimal.valueOf(joursUtilises))
                .divide(BigDecimal.valueOf(joursDuMois), 2, RoundingMode.HALF_UP)
            : mensualiteLineaire;
        int nbPeriodes = prorata ? duree + 1 : duree;

        for (int i = 0; i < nbPeriodes; i++) {
            BigDecimal dotation;
            if (i == nbPeriodes - 1) {
                dotation = base.subtract(cumul);          // solde exact
            } else if (i == 0 && prorata) {
                dotation = premiereMensualite;
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
        return comptabiliserDotationsInterne(jusqua, currentUser.requireUser(), null);
    }

    /** Dernier jour du mois précédent : les dotations d'un mois se passent une fois ce mois écoulé. */
    public static LocalDate finDuMoisEcoule(LocalDate aujourdhui) {
        return aujourdhui.withDayOfMonth(1).minusDays(1);
    }

    /**
     * Passage automatique (voir {@code DotationsPlanificateur} et {@link #creer}) : dotations échues jusqu'à
     * {@code jusqua}, au nom de {@code auteur} ; {@code immobilisationId} non null : ce bien seulement.
     */
    @Transactional
    public DotationsResponse comptabiliserDotationsInterne(LocalDate jusqua, User auteur, Long immobilisationId) {
        List<LigneAmortissement> aPasser = ligneRepository.aComptabiliser(jusqua).stream()
            .filter(l -> immobilisationId == null || immobilisationId.equals(l.getImmobilisation().getId()))
            .toList();

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
        ComptabiliteService.exigerContrepartieHorsTresorerie(contrepartie.getNumero(), "contrepartie d'acquisition");
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
        List<Immobilisation> composants = immobilisationRepository.findByParentId(immo.getId()).stream()
            .filter(c -> !c.estSorti()).toList();
        if (!composants.isEmpty()) {
            throw new TransitionInvalideException("Sortez d'abord les composants de " + immo.getReference() + " : "
                + String.join(", ", composants.stream().map(Immobilisation::getReference).toList()) + ".");
        }

        BigDecimal prix = req.valeurCession() == null ? BigDecimal.ZERO : req.valeurCession();
        TypeCession type = req.typeCession() == null ? TypeCession.HAO : req.typeCession();
        // Dotation complementaire jusqu'au jour de la sortie (C-18) : sans elle, la valeur nette sortie en charge
        // gardait l'amortissement non encore passe de l'exercice.
        PieceComptable complement = dotationComplementaire(immo, req.dateSortie(), auteur);
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
            ecritures.add(ecriture(exigerCompte(type == TypeCession.COURANTE ? COMPTE_VNC_CESSION_COURANTE
                : COMPTE_VNC_CESSION), vnc, BigDecimal.ZERO, libelle, req.dateSortie()));
        }
        // Sortie de la valeur brute.
        ecritures.add(ecriture(immo.getCompteImmobilisation(), BigDecimal.ZERO,
            immo.getValeurAcquisition(), libelle, req.dateSortie()));
        // Volet vente, le cas echeant.
        if (prix.signum() > 0) {
            CompteOHADA contrepartie = resoudreCompte(
                req.compteContrepartieNumero(), COMPTE_CREANCE_CESSION, "contrepartie de cession");
            ComptabiliteService.exigerContrepartieHorsTresorerie(contrepartie.getNumero(), "contrepartie de cession");
            ecritures.add(ecriture(contrepartie, prix, BigDecimal.ZERO, libelle, req.dateSortie()));
            ecritures.add(ecriture(exigerCompte(type == TypeCession.COURANTE ? COMPTE_PRODUIT_CESSION_COURANTE
                : COMPTE_PRODUIT_CESSION), BigDecimal.ZERO, prix, libelle, req.dateSortie()));
        }

        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, req.dateSortie(), ecritures, auteur);

        immo.setStatut(prix.signum() > 0 ? StatutImmobilisation.CEDE : StatutImmobilisation.REBUT);
        immo.setDateSortie(req.dateSortie());
        immo.setValeurCession(prix);
        immo.setTypeCession(type);
        immo.setPieceSortie(piece);

        log.info("Immobilisation sortie [ref={}, statut={}, type={}, vnc={}, prix={}, piece={}, complement={}]",
            immo.getReference(), immo.getStatut(), type, vnc, prix, piece.getReference(),
            complement == null ? "-" : complement.getReference());
        return ImmobilisationResponse.from(immo);
    }

    /**
     * Passe l'amortissement non encore comptabilise jusqu'au jour de la sortie : mois entiers avant le mois de
     * sortie, prorata au jour pour ce mois-la. Les mensualites posterieures sont retirees du plan : le bien ne
     * s'amortit plus. Retourne la piece passee, ou null s'il n'y avait rien a passer.
     */
    PieceComptable dotationComplementaire(Immobilisation immo, LocalDate dateSortie, User auteur) {
        LocalDate moisSortie = dateSortie.withDayOfMonth(1);
        BigDecimal montant = BigDecimal.ZERO;
        List<LigneAmortissement> passees = new ArrayList<>();
        List<LigneAmortissement> retirees = new ArrayList<>();
        for (LigneAmortissement l : immo.getPlanAmortissement()) {
            if (l.isComptabilise()) continue;
            if (l.getPeriode().isBefore(moisSortie)) {
                montant = montant.add(l.getDotation());
                passees.add(l);
            } else if (l.getPeriode().equals(moisSortie)) {
                BigDecimal prorata = l.getDotation().multiply(BigDecimal.valueOf(dateSortie.getDayOfMonth()))
                    .divide(BigDecimal.valueOf(dateSortie.lengthOfMonth()), 2, RoundingMode.HALF_UP);
                l.setDotation(prorata);
                montant = montant.add(prorata);
                passees.add(l);
            } else {
                retirees.add(l);
            }
        }
        immo.getPlanAmortissement().removeAll(retirees);
        if (montant.signum() <= 0 || immo.getCompteDotation() == null || immo.getCompteAmortissement() == null) {
            return null;
        }
        String libelle = "Dotation complementaire a la sortie " + immo.getReference() + " - " + immo.getLibelle();
        PieceComptable piece = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES, libelle,
            dateSortie, List.of(
                ecriture(immo.getCompteDotation(), montant, BigDecimal.ZERO, libelle, dateSortie),
                ecriture(immo.getCompteAmortissement(), BigDecimal.ZERO, montant, libelle, dateSortie)), auteur);
        Instant maintenant = Instant.now();
        BigDecimal cumul = immo.cumulComptabilise();
        for (LigneAmortissement l : passees) {
            cumul = cumul.add(l.getDotation());
            l.setCumul(cumul);
            l.setValeurNette(immo.getValeurAcquisition().subtract(cumul));
            l.setComptabilise(true);
            l.setPiece(piece);
            l.setDateComptabilisation(maintenant);
        }
        return piece;
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

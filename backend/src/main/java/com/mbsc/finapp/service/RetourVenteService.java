package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneRetourVente;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.RetourVente;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.CanalRemboursement;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutRetourVente;
import com.mbsc.finapp.domain.enums.StatutVente;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.vente.DecisionRetourRequest;
import com.mbsc.finapp.dto.vente.RemboursementRetourRequest;
import com.mbsc.finapp.dto.vente.RetourVenteRequest;
import com.mbsc.finapp.dto.vente.RetourVenteResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.RetourVenteRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.emcf.FactureNormaliseeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retour d'une vente deja payee, avec remboursement du client (V134).
 *
 * <ol>
 *   <li>le caissier DEMANDE : lignes et quantites rendues (au plus le vendu moins le deja rendu), motif ; les responsables
 *       concernes sont prevenus ;</li>
 *   <li>le responsable APPROUVE ou REFUSE : RESP_RESTAURANT pour toute demande, RESP_BAR si toutes les lignes sont des
 *       boissons, RESP_CUISINE si ce sont des plats — jamais celui qui a demande ;</li>
 *   <li>le caissier REMBOURSE, dans une seule transaction : piece du journal VENTES miroir de la vente pour la part rendue
 *       (D produits HT, D 4431 TVA / C tresorerie du reglement, ou C 4111 pour une vente a credit pas encore reglee, au taux
 *       de la vente) ; marchandise intacte remise en stock au cout de sa sortie ; vide repris pour une bouteille consignee ;
 *       reflet analytique inverse ; avoir normalise partiel si la vente a une facture normalisee.</li>
 * </ol>
 *
 * <p>Separation des taches : seul le CAISSIER demande et rembourse (jamais l'ADMIN, voir SeparationDesTachesTest), seul un
 * responsable du restaurant decide.</p>
 */
@Service
@RequiredArgsConstructor
public class RetourVenteService {

    private static final Logger log = LoggerFactory.getLogger(RetourVenteService.class);

    private static final BigDecimal CENT = BigDecimal.valueOf(100);
    /** Ce qui compte comme deja rendu pour plafonner un nouveau retour. */
    private static final Set<StatutRetourVente> RENDUS =
        EnumSet.of(StatutRetourVente.DEMANDE, StatutRetourVente.APPROUVE, StatutRetourVente.REMBOURSE);
    private static final String LIEN_RESPONSABLES = "/restaurant/retours";

    private final RetourVenteRepository retourRepository;
    private final VenteRepository venteRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final StockService stockService;
    private final RestaurantService restaurantService;
    private final AnalytiqueVentesService analytique;
    private final FactureNormaliseeService factureNormalisee;
    private final EtablissementTresorerieService etablissements;
    private final ConversionDeviseService conversionDevise;
    private final ReferenceGenerator referenceGenerator;
    private final NotificationService notifications;
    private final CurrentUserProvider currentUser;

    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN', 'RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE')")
    @Transactional(readOnly = true)
    public List<RetourVenteResponse> listerParVente(Long venteId) {
        return retourRepository.findParVente(venteId).stream().map(RetourVenteResponse::from).toList();
    }

    /** Retours que le responsable connecte peut voir (son domaine) : d'abord ceux qui attendent sa decision. */
    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE')")
    @Transactional(readOnly = true)
    public List<RetourVenteResponse> listerPourResponsable() {
        PerimetreRestaurant.Perimetre perimetre = PerimetreRestaurant.restaurantCourant();
        return retourRepository.findParStatuts(EnumSet.allOf(StatutRetourVente.class)).stream()
            .filter(r -> dansLePerimetre(r, perimetre))
            .sorted((a, b) -> {
                boolean da = a.getStatut() == StatutRetourVente.DEMANDE;
                boolean db = b.getStatut() == StatutRetourVente.DEMANDE;
                return da == db ? Long.compare(b.getId(), a.getId()) : (da ? -1 : 1);
            })
            .limit(200)
            .map(RetourVenteResponse::from)
            .toList();
    }

    // ---------------------------------------------------------------------
    // 1. Demande (caissier)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasRole('CAISSIER')")
    @Transactional
    public RetourVenteResponse demander(Long venteId, RetourVenteRequest req) {
        Vente vente = venteRepository.findWithLignesById(venteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("Vente", venteId));
        if (vente.getStatut() != StatutVente.VALIDEE) {
            throw new TransitionInvalideException("Seule une vente validée se rend avec remboursement (état actuel : "
                + vente.getStatut() + ") ; une commande en attente se corrige en retirant la ligne.");
        }
        if (retourRepository.existsByVenteIdAndStatutIn(venteId, EnumSet.of(StatutRetourVente.DEMANDE, StatutRetourVente.APPROUVE))) {
            throw new TransitionInvalideException("Un retour est déjà en cours sur la vente " + vente.getReference()
                + " : attendez sa décision et son remboursement, ou retirez-le.");
        }
        Map<Long, BigDecimal[]> dejaRendu = cumuls(venteId);
        User demandeur = currentUser.requireUser();

        RetourVente retour = RetourVente.builder()
            .reference(referenceGenerator.pourRetourVente())
            .vente(vente)
            .statut(StatutRetourVente.DEMANDE)
            .motif(req.motif().trim())
            .demandeur(demandeur)
            .dateDemande(Instant.now())
            .build();
        Set<Long> vues = new HashSet<>();
        for (RetourVenteRequest.Ligne demande : req.lignes()) {
            if (!vues.add(demande.ligneVenteId())) {
                throw new IllegalArgumentException("Une ligne de la vente ne figure qu'une fois dans un retour.");
            }
            LigneVente ligne = vente.getLignes().stream().filter(l -> l.getId().equals(demande.ligneVenteId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("La ligne " + demande.ligneVenteId() + " n'appartient pas à la vente "
                    + vente.getReference() + "."));
            if (ligne.getCamion() != null) {
                throw new IllegalArgumentException("Un camion de minerais (" + ligne.getDesignation() + ") ne se rend pas par un "
                    + "retour de caisse.");
            }
            BigDecimal[] deja = dejaRendu.getOrDefault(ligne.getId(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal reste = ligne.getQuantite().subtract(deja[0]);
            if (demande.quantite().compareTo(reste) > 0) {
                throw new IllegalArgumentException("On ne peut rendre que " + nombre(reste.max(BigDecimal.ZERO)) + " × « "
                    + ligne.getDesignation() + " » (vendu : " + nombre(ligne.getQuantite()) + ", déjà rendu ou en cours : "
                    + nombre(deja[0]) + ").");
            }
            BigDecimal[] montants = montantsRendus(ligne, demande.quantite(), deja, vente.getTauxTvaApplique());
            retour.addLigne(LigneRetourVente.builder()
                .ligneVente(ligne)
                .quantite(demande.quantite())
                .remiseEnStock(remiseEnStock(ligne.getArticle(), demande.remiseEnStock()))
                .montantHt(montants[0])
                .montantTva(montants[1])
                .build());
        }
        totaliser(retour);
        RetourVente saved = retourRepository.save(retour);

        String detail = "Retour " + saved.getReference() + " demandé sur la vente " + vente.getReference() + " (" + designation(saved)
            + ") : " + montant(saved.getMontantTtc(), vente) + " à rembourser — motif : " + saved.getMotif() + ".";
        AuditService.detailler(detail);
        for (RoleType role : responsables(saved)) {
            notifications.notifierRole(role, TypeNotification.RETOUR_VENTE_A_APPROUVER, "Retour de vente à approuver",
                "Le caissier demande le retour " + saved.getReference() + " sur la vente " + vente.getReference() + " ("
                    + designation(saved) + ", " + montant(saved.getMontantTtc(), vente) + ") : " + saved.getMotif(),
                LIEN_RESPONSABLES, null);
        }
        log.info("Retour de vente demandé [retour={}, vente={}, TTC={}]", saved.getReference(), vente.getReference(),
            saved.getMontantTtc());
        return RetourVenteResponse.from(saved);
    }

    /** Le caissier retire une demande pas encore remboursée. */
    @PreAuthorize("hasRole('CAISSIER')")
    @Transactional
    public RetourVenteResponse annuler(Long id) {
        RetourVente r = charger(id);
        if (!r.getStatut().estOuvert()) {
            throw new TransitionInvalideException("Le retour " + r.getReference() + " est " + r.getStatut()
                + " : il ne se retire plus.");
        }
        r.setStatut(StatutRetourVente.ANNULE);
        AuditService.detailler("Retour " + r.getReference() + " (vente " + r.getVente().getReference() + ") retiré par le caissier.");
        log.info("Retour de vente retiré [retour={}]", r.getReference());
        return RetourVenteResponse.from(r);
    }

    // ---------------------------------------------------------------------
    // 2. Decision du responsable
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE')")
    @Transactional
    public RetourVenteResponse approuver(Long id, DecisionRetourRequest req) {
        RetourVente r = chargerPourDecision(id);
        r.setStatut(StatutRetourVente.APPROUVE);
        r.setApprobateur(currentUser.requireUser());
        r.setDateDecision(Instant.now());
        r.setCommentaireDecision(commentaire(req));
        AuditService.detailler("Retour " + r.getReference() + " (vente " + r.getVente().getReference() + ", "
            + montant(r.getMontantTtc(), r.getVente()) + ") approuvé.");
        prevenirCaisse(r, TypeNotification.RETOUR_VENTE_APPROUVE, "Retour de vente approuvé",
            "Le retour " + r.getReference() + " sur la vente " + r.getVente().getReference() + " est approuvé : remboursez "
                + montant(r.getMontantTtc(), r.getVente()) + " au client.");
        log.info("Retour de vente approuvé [retour={}]", r.getReference());
        return RetourVenteResponse.from(r);
    }

    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE')")
    @Transactional
    public RetourVenteResponse refuser(Long id, DecisionRetourRequest req) {
        String motif = commentaire(req);
        if (motif == null) {
            throw new IllegalArgumentException("Indiquez pourquoi le retour est refusé.");
        }
        RetourVente r = chargerPourDecision(id);
        r.setStatut(StatutRetourVente.REFUSE);
        r.setApprobateur(currentUser.requireUser());
        r.setDateDecision(Instant.now());
        r.setCommentaireDecision(motif);
        AuditService.detailler("Retour " + r.getReference() + " (vente " + r.getVente().getReference() + ") refusé : " + motif);
        prevenirCaisse(r, TypeNotification.RETOUR_VENTE_REFUSE, "Retour de vente refusé",
            "Le retour " + r.getReference() + " sur la vente " + r.getVente().getReference() + " est refusé : " + motif);
        log.info("Retour de vente refusé [retour={}]", r.getReference());
        return RetourVenteResponse.from(r);
    }

    private RetourVente chargerPourDecision(Long id) {
        RetourVente r = charger(id);
        if (r.getStatut() != StatutRetourVente.DEMANDE) {
            throw new TransitionInvalideException("Le retour " + r.getReference() + " est déjà " + r.getStatut() + ".");
        }
        if (!dansLePerimetre(r, PerimetreRestaurant.restaurantCourant())) {
            throw new AccessDeniedException("Ce retour comporte des articles hors de votre domaine : il revient au responsable "
                + "du restaurant (ou du bar pour des boissons, de la cuisine pour des plats).");
        }
        User moi = currentUser.requireUser();
        if (r.getDemandeur() != null && r.getDemandeur().getId().equals(moi.getId())) {
            throw new AccessDeniedException("Un retour ne s'approuve pas par celui qui l'a demandé.");
        }
        return r;
    }

    // ---------------------------------------------------------------------
    // 3. Remboursement (caissier)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasRole('CAISSIER')")
    @Transactional
    public RetourVenteResponse rembourser(Long id, RemboursementRetourRequest req) {
        RetourVente r = charger(id);
        if (r.getStatut() != StatutRetourVente.APPROUVE) {
            throw new TransitionInvalideException("Seul un retour approuvé par le responsable se rembourse (état actuel : "
                + r.getStatut() + ").");
        }
        Vente vente = r.getVente();
        if (vente.getStatut() != StatutVente.VALIDEE) {
            throw new TransitionInvalideException("La vente " + vente.getReference() + " n'est plus validée (" + vente.getStatut()
                + ") : le retour ne peut plus être remboursé.");
        }
        User caissier = currentUser.requireUser();
        LocalDate date = req == null || req.dateRemboursement() == null ? LocalDate.now() : req.dateRemboursement();
        if (date.isBefore(vente.getDateVente())) {
            throw new IllegalArgumentException("Le remboursement ne peut pas précéder la vente (" + vente.getDateVente() + ").");
        }

        // Canal : la créance d'une vente à crédit pas encore réglée diminue (aucun argent ne sort) ; sinon l'argent sort par
        // le canal choisi, celui du règlement par défaut.
        CanalRemboursement canal = RetourVenteResponse.canalPropose(vente) == CanalRemboursement.CREANCE
            ? CanalRemboursement.CREANCE
            : (req != null && req.canal() != null ? req.canal() : RetourVenteResponse.canalPropose(vente));
        if (canal == CanalRemboursement.CREANCE && RetourVenteResponse.canalPropose(vente) != CanalRemboursement.CREANCE) {
            throw new IllegalArgumentException("La vente " + vente.getReference() + " est déjà payée : remboursez le client en "
                + "caisse, par banque ou par mobile money.");
        }
        EtablissementTresorerie etablissement = null;
        CompteOHADA contrepartie;
        switch (canal) {
            case CAISSE -> contrepartie = compteParNumero(VenteService.COMPTE_CAISSE);
            case CREANCE -> contrepartie = compteParNumero(VenteService.COMPTE_CLIENTS);
            case BANQUE, MOBILE_MONEY -> {
                TypeEtablissement type = canal == CanalRemboursement.BANQUE ? TypeEtablissement.BANQUE : TypeEtablissement.MOBILE_MONEY;
                Long etabId = req != null && req.etablissementId() != null ? req.etablissementId()
                    : (vente.getEtablissement() != null && memeCanal(vente.getModeReglement(), canal) ? vente.getEtablissement().getId() : null);
                if (etabId == null) {
                    throw new IllegalArgumentException("Précisez " + (canal == CanalRemboursement.BANQUE ? "la banque" : "l'opérateur de "
                        + "mobile money") + " du remboursement.");
                }
                etablissement = etablissements.requireEtablissement(etabId, type);
                contrepartie = etablissement.getCompte();
            }
            default -> throw new IllegalStateException("Canal inconnu : " + canal);
        }

        // a. Pièce miroir de la vente pour la part rendue.
        String libelle = "Retour " + r.getReference() + " - vente " + vente.getReference() + " - " + vente.designationClient();
        r.setPiece(comptabilite.creerPieceInterne(JournalComptable.VENTES, libelle, date,
            ecrituresRemboursement(r, contrepartie, libelle, date), caissier));

        // b. Marchandise intacte remise en stock, au coût de sa sortie ; c. vide repris pour une bouteille consignée.
        for (LigneRetourVente l : r.getLignes()) {
            Article article = l.getLigneVente().getArticle();
            if (!l.isRemiseEnStock() || !article.getType().estStocke() || vente.getEntrepot() == null) {
                continue;
            }
            if (article.getCompteCharge() == null) {
                throw new IllegalStateException("« " + article.getLibelle() + " » n'a pas de compte de variation : impossible de le "
                    + "remettre en stock.");
            }
            MouvementStock entree = stockService.enregistrerEntreeRestaurantInterne(date,
                "Retour " + r.getReference() + " - " + article.getLibelle() + " (vente " + vente.getReference() + ")",
                vente.getEntrepot(), article.getCompteCharge(), article, l.getQuantite(), coutDeSortie(vente, article), caissier);
            l.setMouvementStock(entree);
            restaurantService.reprendreVidesSurRetourInterne(article, l.getQuantite(), date,
                "Retour " + r.getReference() + " (vente " + vente.getReference() + ")", vente, caissier);
        }

        r.setStatut(StatutRetourVente.REMBOURSE);
        r.setCaissier(caissier);
        r.setDateRemboursement(date);
        r.setCanal(canal);
        r.setEtablissement(etablissement);

        // d. Reflet analytique inverse (ne bloque jamais) ; e. avoir normalisé partiel si la vente a une facture normalisée.
        r.setPieceAnalytique(analytique.refleterRetourInterne(r, caissier));
        factureNormalisee.surRetourRembourse(r);

        String detail = "Retour " + r.getReference() + " remboursé (" + libelleCanal(canal, etablissement) + ") : "
            + montant(r.getMontantTtc(), vente) + " — vente " + vente.getReference() + ", " + designation(r) + " — approuvé par "
            + nomDe(r.getApprobateur()) + ".";
        AuditService.detailler(detail);
        for (RoleType role : responsables(r)) {
            notifications.notifierRole(role, TypeNotification.RETOUR_VENTE_REMBOURSE, "Retour de vente remboursé",
                "Le retour " + r.getReference() + " (vente " + vente.getReference() + ", " + montant(r.getMontantTtc(), vente)
                    + ") est remboursé (" + libelleCanal(canal, etablissement) + ").", LIEN_RESPONSABLES, null);
        }
        log.info("Retour de vente remboursé [retour={}, canal={}, piece={}]", r.getReference(), canal,
            r.getPiece() == null ? null : r.getPiece().getReference());
        return RetourVenteResponse.from(r);
    }

    /**
     * Écritures du remboursement, miroir de celles de la vente ({@code VenteService.construireEcrituresVente}) pour la part
     * rendue : un débit par compte de produit (HT), un débit de TVA collectée, un crédit de la contrepartie pour leur somme
     * exacte — chaque débit converti au taux de la vente, le crédit valant leur somme (pièce équilibrée, sans écart d'arrondi).
     */
    List<EcritureGrandLivre> ecrituresRemboursement(RetourVente r, CompteOHADA contrepartie, String libelle, LocalDate date) {
        Vente vente = r.getVente();
        Map<CompteOHADA, BigDecimal> produits = new LinkedHashMap<>();
        for (LigneRetourVente l : r.getLignes()) {
            CompteOHADA compte = l.getLigneVente().getArticle().getCompteProduit();
            if (compte == null) {
                throw new IllegalArgumentException("Aucun compte de produit pour « " + l.getLigneVente().getDesignation() + " ».");
            }
            produits.merge(compte, l.getMontantHt(), BigDecimal::add);
        }
        List<EcritureGrandLivre> debits = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<CompteOHADA, BigDecimal> p : produits.entrySet()) {
            BigDecimal base = versDeviseBase(p.getValue(), vente);
            total = total.add(base);
            debits.add(ecriture(p.getKey(), base, BigDecimal.ZERO, p.getValue(), libelle, vente, date));
        }
        if (r.getMontantTva().signum() > 0) {
            BigDecimal base = versDeviseBase(r.getMontantTva(), vente);
            total = total.add(base);
            debits.add(ecriture(compteParNumero(VenteService.COMPTE_TVA_FACTUREE), base, BigDecimal.ZERO, r.getMontantTva(), libelle,
                vente, date));
        }
        List<EcritureGrandLivre> ecritures = new ArrayList<>(debits);
        ecritures.add(ecriture(contrepartie, BigDecimal.ZERO, total, r.getMontantTtc(), libelle, vente, date));
        return ecritures;
    }

    // ---------------------------------------------------------------------
    // Regles
    // ---------------------------------------------------------------------

    /**
     * HT et TVA de la part rendue d'une ligne : proportionnels au prix, et pour le dernier retour d'une ligne (tout ce qui
     * restait), le reste exact de ses montants — la somme des retours egale alors la ligne, sans ecart d'arrondi.
     *
     * @param deja [quantite, HT, TVA] deja rendus (ou en cours) sur cette ligne
     */
    static BigDecimal[] montantsRendus(LigneVente ligne, BigDecimal quantite, BigDecimal[] deja, BigDecimal tauxTva) {
        if (quantite.compareTo(ligne.getQuantite().subtract(deja[0])) == 0) {
            return new BigDecimal[] {ligne.getMontantHt().subtract(deja[1]), ligne.getMontantTva().subtract(deja[2])};
        }
        BigDecimal ht = ligne.getPrixUnitaire().multiply(quantite).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tva = ligne.isSoumisTva() && tauxTva != null && tauxTva.signum() > 0
            ? ht.multiply(tauxTva).divide(CENT, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        return new BigDecimal[] {ht, tva};
    }

    /** Remise en stock par defaut : une boisson rendue est intacte, un plat servi ne revient pas en stock. */
    static boolean remiseEnStock(Article article, Boolean demandee) {
        if (article == null || !article.getType().estStocke() || article.getType() == TypeArticle.SERVICE) {
            return false;
        }
        if (demandee != null) {
            return demandee;
        }
        return article.getType() == TypeArticle.BOISSON;
    }

    /** Responsables a prevenir et a qui revient la decision : le restaurant toujours, le bar et la cuisine selon les lignes. */
    static Set<RoleType> responsables(RetourVente r) {
        Set<RoleType> roles = new LinkedHashSet<>();
        roles.add(RoleType.RESP_RESTAURANT);
        for (LigneRetourVente l : r.getLignes()) {
            TypeArticle type = l.getLigneVente().getArticle().getType();
            if (type == TypeArticle.BOISSON) roles.add(RoleType.RESP_BAR);
            if (type == TypeArticle.PLAT) roles.add(RoleType.RESP_CUISINE);
        }
        return roles;
    }

    /** true si toutes les lignes du retour relevent du domaine du responsable (bar : boissons ; cuisine : plats ; restaurant : tout). */
    static boolean dansLePerimetre(RetourVente r, PerimetreRestaurant.Perimetre perimetre) {
        return r.getLignes().stream().allMatch(l -> perimetre.autorise(l.getLigneVente().getArticle().getType()));
    }

    private static void totaliser(RetourVente r) {
        BigDecimal ht = BigDecimal.ZERO;
        BigDecimal tva = BigDecimal.ZERO;
        for (LigneRetourVente l : r.getLignes()) {
            ht = ht.add(l.getMontantHt());
            tva = tva.add(l.getMontantTva());
        }
        r.setMontantHt(ht);
        r.setMontantTva(tva);
        r.setMontantTtc(ht.add(tva));
    }

    private Map<Long, BigDecimal[]> cumuls(Long venteId) {
        Map<Long, BigDecimal[]> m = new HashMap<>();
        for (Object[] c : retourRepository.cumulsParLigne(venteId, RENDUS)) {
            m.put((Long) c[0], new BigDecimal[] {(BigDecimal) c[1], (BigDecimal) c[2], (BigDecimal) c[3]});
        }
        return m;
    }

    /** Cout unitaire auquel l'article est sorti du stock a la vente (CMP du moment), lu sur le mouvement de la vente. */
    private static BigDecimal coutDeSortie(Vente vente, Article article) {
        if (vente.getMouvement() != null) {
            for (LigneMouvementStock l : vente.getMouvement().getLignes()) {
                if (l.getArticle() != null && l.getArticle().getId().equals(article.getId()) && l.getCoutUnitaire() != null) {
                    return l.getCoutUnitaire();
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private void prevenirCaisse(RetourVente r, TypeNotification type, String titre, String message) {
        String lien = "/ventes/" + r.getVente().getId();
        notifications.notifierRole(RoleType.CAISSIER, type, titre, message, lien, null);
        User demandeur = r.getDemandeur();
        boolean dejaPrevenu = demandeur != null && demandeur.getRoles() != null
            && demandeur.getRoles().stream().anyMatch(role -> role.getNom() == RoleType.CAISSIER);
        if (!dejaPrevenu) {
            notifications.notifierUtilisateur(demandeur, type, titre, message, lien, null);
        }
    }

    private RetourVente charger(Long id) {
        return retourRepository.findAvecLignes(id).orElseThrow(() -> RessourceIntrouvableException.of("RetourVente", id));
    }

    private CompteOHADA compteParNumero(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new IllegalStateException("Compte " + numero + " absent du plan comptable"));
    }

    /** Montant de la devise de la vente vers la devise de base, au taux de la vente (comme sa pièce). */
    private BigDecimal versDeviseBase(BigDecimal montant, Vente vente) {
        if (montant == null || vente.getDevise() == null || vente.getDevise() == ConversionDeviseService.DEVISE_BASE) {
            return montant;
        }
        BigDecimal taux = vente.getTauxJournalier() != null && vente.getTauxJournalier().signum() > 0
            ? vente.getTauxJournalier()
            : conversionDevise.tauxCourant();
        if (taux == null || taux.signum() <= 0) {
            throw new IllegalStateException("Aucun taux de change n'est rattaché à la vente " + vente.getReference() + ".");
        }
        return conversionDevise.enDeviseBase(montant, vente.getDevise(), taux).montantBase();
    }

    private static EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit, BigDecimal montantOrigine,
                                               String libelle, Vente vente, LocalDate date) {
        boolean convertie = vente.getDevise() != null && vente.getDevise() != ConversionDeviseService.DEVISE_BASE;
        var builder = EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .dateEcriture(date)
            .tauxApplique(vente.getTauxJournalier());
        if (convertie) {
            builder.devise(vente.getDevise().name()).montantDevise(montantOrigine);
        }
        return builder.build();
    }

    private static boolean memeCanal(ModeReglement mode, CanalRemboursement canal) {
        return (mode == ModeReglement.BANQUE && canal == CanalRemboursement.BANQUE)
            || (mode == ModeReglement.MOBILE_MONEY && canal == CanalRemboursement.MOBILE_MONEY);
    }

    private static String libelleCanal(CanalRemboursement canal, EtablissementTresorerie etablissement) {
        return switch (canal) {
            case CAISSE -> "en caisse";
            case CREANCE -> "créance du client diminuée";
            case BANQUE -> "par la banque " + (etablissement == null ? "" : etablissement.getNom());
            case MOBILE_MONEY -> "par mobile money " + (etablissement == null ? "" : etablissement.getNom());
        };
    }

    private static String designation(RetourVente r) {
        List<String> parties = new ArrayList<>();
        for (LigneRetourVente l : r.getLignes()) {
            parties.add(nombre(l.getQuantite()) + " × " + l.getLigneVente().getDesignation());
        }
        return String.join(", ", parties);
    }

    private static String montant(BigDecimal m, Vente v) {
        return m.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + (v.getDevise() == null ? "" : v.getDevise().name());
    }

    private static String nombre(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static String nomDe(User u) {
        if (u == null) return "?";
        String complet = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return complet.isEmpty() ? u.getEmail() : complet;
    }

    private static String commentaire(DecisionRetourRequest req) {
        return req == null || req.commentaire() == null || req.commentaire().isBlank() ? null : req.commentaire().trim();
    }
}

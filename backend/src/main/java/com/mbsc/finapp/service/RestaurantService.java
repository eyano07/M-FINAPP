package com.mbsc.finapp.service;

import com.mbsc.finapp.service.DocumentationCompteService.RoleCompteArticle;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EmballageBoisson;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneProduction;
import com.mbsc.finapp.domain.LigneRecette;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.Production;
import com.mbsc.finapp.domain.SalleRestaurant;
import com.mbsc.finapp.domain.SortieBoisson;
import com.mbsc.finapp.domain.SortiePlat;
import com.mbsc.finapp.domain.SortieProvision;
import com.mbsc.finapp.domain.StockGrandLivre;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.TableRestaurant;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.FormeTable;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.StatutProduction;
import com.mbsc.finapp.domain.enums.StatutVente;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;
import com.mbsc.finapp.domain.enums.TypeSortieProvision;
import com.mbsc.finapp.dto.logistique.ArticleRequest;
import com.mbsc.finapp.dto.logistique.ArticleResponse;
import com.mbsc.finapp.dto.logistique.CodeArticleSuggereResponse;
import com.mbsc.finapp.dto.logistique.EntrepotResponse;
import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;
import com.mbsc.finapp.dto.logistique.StockNiveauResponse;
import com.mbsc.finapp.dto.restaurant.AnalyseVentesResponse;
import com.mbsc.finapp.dto.restaurant.EmballageRequest;
import com.mbsc.finapp.dto.restaurant.EmballageResponse;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageRequest;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageResponse;
import com.mbsc.finapp.dto.restaurant.MouvementProvisionResponse;
import com.mbsc.finapp.dto.restaurant.LotStockResponse;
import com.mbsc.finapp.dto.restaurant.LigneProductionRequest;
import com.mbsc.finapp.dto.restaurant.LigneRecetteRequest;
import com.mbsc.finapp.dto.restaurant.LigneRecetteResponse;
import com.mbsc.finapp.dto.restaurant.PlanSalleRequest;
import com.mbsc.finapp.dto.restaurant.PlatPerimeResponse;
import com.mbsc.finapp.dto.restaurant.ProductionRequest;
import com.mbsc.finapp.dto.restaurant.ProductionResponse;
import com.mbsc.finapp.dto.restaurant.ProvisionSortieRequest;
import com.mbsc.finapp.dto.restaurant.RecetteRequest;
import com.mbsc.finapp.dto.restaurant.RecetteResponse;
import com.mbsc.finapp.dto.restaurant.RetraitPerimesRequest;
import com.mbsc.finapp.dto.restaurant.SalleRequest;
import com.mbsc.finapp.dto.restaurant.SalleResponse;
import com.mbsc.finapp.dto.restaurant.SortieBoissonRequest;
import com.mbsc.finapp.dto.restaurant.SortieBoissonResponse;
import com.mbsc.finapp.dto.restaurant.SortiePlatRequest;
import com.mbsc.finapp.dto.restaurant.SortiePlatResponse;
import com.mbsc.finapp.dto.restaurant.TableRequest;
import com.mbsc.finapp.dto.restaurant.TableResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse.MouvementJourResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse.ProvisionStatResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse;
import com.mbsc.finapp.dto.vente.VenteResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.BoissonStatResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.PerteTypeResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.VentesJourResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.LigneRecetteRepository;
import com.mbsc.finapp.repository.LotStockRepository;
import com.mbsc.finapp.repository.MouvementEmballageRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.repository.ProductionRepository;
import com.mbsc.finapp.repository.SalleRestaurantRepository;
import com.mbsc.finapp.repository.SortiePlatRepository;
import com.mbsc.finapp.repository.SortieProvisionRepository;
import com.mbsc.finapp.repository.StockGrandLivreRepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.repository.TableRestaurantRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Module Restaurant : carte (plats, boissons) et stock de bouteilles vides.
 *
 * <p>La carte n'a pas ses propres tables : un plat ou une boisson est un
 * {@link Article} de type PLAT ou BOISSON, ce qui lui donne gratuitement le
 * moteur de stock complet. Ce service delegue a {@link StockService} par ses
 * variantes internes, car le module porte sa propre autorisation et son propre
 * prefixe d'URL — le responsable restaurant n'a pas le module LOGISTIQUE.</p>
 *
 * <p>Le stock d'emballages suit le cycle de la consigne : chaque bouteille
 * vendue revient en stock de vides, et acheter des casiers pleins fait rendre
 * l'equivalent en vides. Un seul compteur par boisson ; le casier est une
 * unite d'affichage derivee.</p>
 *
 * <p>Tout achat de boissons passe desormais par une note de frais (module
 * Notes de frais, circuit Creation-DFIN-DA-DFIN-Caisse) : ce service ne
 * reçoit plus de réception directe. L'entrée en stock et, le cas échéant,
 * l'échange de consigne sont déclenchés au paiement par {@code CaisseService}
 * via {@link #enregistrerAchatVidesDepuisNoteFraisInterne}. Il en va de même des provisions : leurs
 * entrées en stock viennent uniquement de l'achat (page Achat de provisions, mêmes notes de frais) ; seules
 * leurs sorties se saisissent ici.</p>
 */
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantService.class);

    /** Les seuls types d'article que ce module manipule. */
    private static final Set<TypeArticle> TYPES_CARTE = EnumSet.of(TypeArticle.PLAT, TypeArticle.BOISSON);

    private final ArticleRepository articleRepository;
    private final EmballageBoissonRepository emballageRepository;
    private final MouvementEmballageRepository mouvementRepository;
    private final EntrepotRepository entrepotRepository;
    private final CompteOHADARepository compteRepository;
    private final VenteRepository venteRepository;
    private final MouvementStockRepository mouvementStockRepository;
    private final SalleRestaurantRepository salleRepository;
    private final TableRestaurantRepository tableRepository;
    private final LigneRecetteRepository ligneRecetteRepository;
    private final ProductionRepository productionRepository;
    private final NoteFraisRepository noteFraisRepository;
    private final StockNiveauRepository stockNiveauRepository;
    /** Historique des provisions, lu en entités pour savoir quels mouvements l'écran peut annuler. */
    private final StockGrandLivreRepository stockGrandLivreRepository;
    private final StockService stockService;
    /** Type et observation des sorties de provisions, que le mouvement de stock ne sait pas porter. */
    private final SortieProvisionRepository sortieProvisionRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    /** Fiche de documentation des comptes propres à chaque article (voir genererCompteDedie). */
    private final DocumentationCompteService documentation;
    /** Résout le taux du jour pour les réceptions de provisions cotées en FC. */
    private final ConversionDeviseService conversionDevise;
    /** Lots actifs (date d'achat, fournisseur) — suivi de gestion, voir LotStockService. */
    private final LotStockRepository lotStockRepository;
    /** Sorties de plats hors vente (périmé, renversé, offert...) — voir {@link #enregistrerSortiePlat}. */
    private final SortiePlatRepository sortiePlatRepository;
    /** Pertes des boissons sans consigne (V134). */
    private final com.mbsc.finapp.repository.SortieBoissonRepository sortieBoissonRepository;
    /** Stock mis de côté par les commandes en attente : on ne le perd ni ne l'offre (V134). */
    private final ReservationStockService reservations;
    /** Verrou des retraits de plats périmés (voir {@link #retirerPlatsPerimesInterne}). */
    private final ParametresRestaurantRepository parametresRestaurantRepository;

    // Trois perimetres de garde. Le module RESTAURANT est plat (ModuleAccessFilter ne distingue ni la cuisine ni le
    // bar) : c'est ici que les responsables cuisine et bar sont separes, sur le type des articles de chaque ecran
    // (voir PerimetreRestaurant, qui filtre ensuite les listes mixtes : carte, stock, analyses).
    //   - LECTURE        : tout le restaurant — plan des salles, tables et commandes (ni cuisine ni bar n'y accedent) ;
    //   - *_CUISINE      : plats, fiches techniques, production, provisions, sorties de plats ;
    //   - *_BAR          : tableau de bord des boissons, lots de boissons, bouteilles vides ;
    //   - LECTURE_CARTE  : les ecrans partages (carte, stock, entrepots), filtres par domaine dans la methode.
    private static final String LECTURE =
        "hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final String LECTURE_CARTE =
        "hasAnyRole('RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final String LECTURE_CUISINE =
        "hasAnyRole('RESP_RESTAURANT', 'RESP_CUISINE', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final String LECTURE_BAR =
        "hasAnyRole('RESP_RESTAURANT', 'RESP_BAR', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final String ECRITURE_CUISINE = "hasAnyRole('RESP_RESTAURANT', 'RESP_CUISINE', 'ADMIN')";
    private static final String ECRITURE_BAR = "hasAnyRole('RESP_RESTAURANT', 'RESP_BAR', 'ADMIN')";
    /**
     * Occuper/liberer une table est une action de service (le caissier y a
     * autant besoin qu'au responsable restaurant), a distinguer de {@link
     * #ECRITURE_CUISINE} et {@link #ECRITURE_BAR} qui couvrent la production,
     * les provisions et les emballages — restes hors du quotidien du caissier.
     */
    private static final String ECRITURE_STATUT_TABLE = "hasAnyRole('RESP_RESTAURANT', 'CAISSIER', 'ADMIN')";
    /**
     * Analyse des ventes : un outil de pilotage de l'offre, reserve parmi les
     * lecteurs du module ({@link #LECTURE}) a ceux qui la pilotent — le
     * responsable restaurant, les responsables cuisine et bar (chacun pour son
     * domaine : l'analyse est filtree sur le type des articles, voir
     * {@link PerimetreRestaurant}), le DFIN et le DG. Exposee sous /restaurant
     * comme le reste du module, donc soumise a son activation et a ses
     * permissions (le DFIN et le DG y ont la lecture).
     */
    private static final String LECTURE_ANALYSES =
        "hasAnyRole('RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'DFIN', 'DG', 'ADMIN')";
    /**
     * Creation et modification des plats/boissons de la carte (prix de vente,
     * imputation comptable) : reservees a l'administrateur — le responsable
     * restaurant garde la main sur les ventes, receptions, casse et pertes du
     * quotidien, mais pas sur la definition de ce qui compose la carte.
     */
    private static final String ECRITURE_CARTE = "hasRole('ADMIN')";
    /** Plan des salles et tables : meme logique que la carte, reservee a l'administrateur. */
    private static final String ECRITURE_SALLES = "hasRole('ADMIN')";

    // ---------------------------------------------------------------------
    // Carte : plats et boissons
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE_CARTE)
    @Transactional(readOnly = true)
    public List<ArticleResponse> listerCarte() {
        // Le responsable cuisine ne recoit que les plats, le responsable bar que les boissons.
        PerimetreRestaurant.Perimetre perimetre = PerimetreRestaurant.restaurantCourant();
        return articleRepository.findAllWithComptes().stream()
            .filter(a -> TYPES_CARTE.contains(a.getType()))
            .filter(a -> perimetre.autorise(a.getType()))
            .map(ArticleResponse::from)
            .toList();
    }

    /** Racines des comptes dedies d'un PLAT — voir {@link #genererCompteDedie}. */
    private static final String RACINE_COMPTE_STOCK_PLAT = "361";
    private static final String RACINE_COMPTE_CHARGE_PLAT = "736";
    private static final String RACINE_COMPTE_PRODUIT_PLAT = "7021";
    /** Racines des comptes dedies d'une BOISSON. Le compte d'achat (6011) est partage avec PLAT et les provisions : un seul espace de numerotation pour tout achat du module. */
    private static final String RACINE_COMPTE_STOCK_BOISSON = "3111";
    private static final String RACINE_COMPTE_CHARGE_BOISSON = "6031";
    private static final String RACINE_COMPTE_PRODUIT_BOISSON = "7011";
    /**
     * Comptes des boissons perdues autrement que par la casse, créés par V120 — voir {@link #compteChargePerte}. Une boisson
     * offerte est une charge de relations publiques, sous 6276 « Cadeaux à la clientèle » ; une boisson périmée reste en
     * variation des stocks de marchandises, à part de celle de chaque boisson. Le 6031.1 appartient aux minerais (V67).
     */
    static final String COMPTE_BOISSONS_OFFERTES = "6276.1";
    static final String COMPTE_BOISSONS_PERIMEES = "6031.2";
    /**
     * Comptes des sorties de plats hors vente, un par motif, créés par V121 — voir {@link #compteSortiePlat}. Un plat offert est
     * une charge de relations publiques (sous 6276 « Cadeaux à la clientèle », comme une boisson offerte) ; un repas du
     * personnel un avantage en nature (sous 6617) ; les pertes (périmé, moisi, renversé, brûlé, autre) restent en variation des
     * stocks de produits finis, sous 736, à part de la sortie des plats vendus.
     */
    static final String COMPTE_PLATS_OFFERTS = "6276.2";
    static final String COMPTE_PLATS_PERIMES = "736.1";
    static final String COMPTE_PLATS_MOISIS = "736.2";
    static final String COMPTE_PLATS_RENVERSES = "736.3";
    static final String COMPTE_PLATS_BRULES = "736.4";
    static final String COMPTE_AUTRES_SORTIES_PLATS = "736.5";
    static final String COMPTE_REPAS_PERSONNEL = "6617.1";

    @PreAuthorize(ECRITURE_CARTE)
    @Transactional
    public ArticleResponse creerArticleCarte(ArticleRequest req) {
        exigerTypeCarte(req.type());
        boolean estPlat = req.type() == TypeArticle.PLAT;
        ArticleRequest reqAvecComptes = new ArticleRequest(
            req.code(), req.libelle(), req.uniteMesure(), req.type(), req.categorie(), req.societe(), req.entrepotId(),
            genererCompteDedie(estPlat ? RACINE_COMPTE_STOCK_PLAT : RACINE_COMPTE_STOCK_BOISSON, RoleCompteArticle.STOCK, req).getNumero(),
            genererCompteDedie(estPlat ? RACINE_COMPTE_CHARGE_PLAT : RACINE_COMPTE_CHARGE_BOISSON, RoleCompteArticle.VARIATION, req).getNumero(),
            genererCompteDedie(estPlat ? RACINE_COMPTE_PRODUIT_PLAT : RACINE_COMPTE_PRODUIT_BOISSON, RoleCompteArticle.PRODUIT, req).getNumero(),
            genererCompteDedie(RACINE_COMPTE_ACHAT_MARCHANDISE, RoleCompteArticle.ACHAT, req).getNumero(),
            req.prixVente(), req.prixAchat(), req.minerais(), req.soumisTva(), req.stockMin(), req.actif(), req.sansConsigne()
        );
        ArticleResponse cree = stockService.creerArticleInterne(reqAvecComptes);
        log.info("Article de carte créé [code={}, type={}]", cree.code(), cree.type());
        return cree;
    }

    /**
     * Code proposé pour un nouvel article de la carte, déduit de son libellé (et de sa société pour une
     * boisson ; un plat reçoit le préfixe {@code PLAT}, voir {@link GenerateurCodeArticle}) et libre au
     * moment de l'appel : l'écran de création l'affiche au fil de la saisie. Il est à nouveau demandé
     * juste avant l'enregistrement, et l'unicité reste contrôlée à la création.
     *
     * @param type {@code PLAT} ou {@code BOISSON} ; absent, c'est une boisson (comportement d'origine)
     */
    @PreAuthorize(ECRITURE_CARTE)
    @Transactional(readOnly = true)
    public CodeArticleSuggereResponse suggererCodeArticleCarte(String libelle, String societe, TypeArticle type) {
        String code = type == TypeArticle.PLAT
            ? GenerateurCodeArticle.genererPlat(libelle, articleRepository::existsByCode)
            : GenerateurCodeArticle.generer(libelle, societe, articleRepository::existsByCode);
        return new CodeArticleSuggereResponse(code);
    }

    @PreAuthorize(ECRITURE_CARTE)
    @Transactional
    public ArticleResponse modifierArticleCarte(Long id, ArticleRequest req) {
        exigerTypeCarte(req.type());
        Article existant = exigerArticleDeLaCarte(id);
        if (existant.getType() == TypeArticle.BOISSON && Boolean.TRUE.equals(req.sansConsigne()) && !existant.isSansConsigne()) {
            retirerConditionnementInutilise(existant);
        }
        return stockService.modifierArticleInterne(id, completerParLExistant(existant, req));
    }

    /**
     * Une boisson qui devient « sans consigne » n'a plus de casier : un conditionnement jamais utilisé (aucun vide en stock,
     * aucun mouvement) se retire tout seul ; sinon on refuse, son historique de vides devant rester lisible (V134).
     */
    private void retirerConditionnementInutilise(Article boisson) {
        emballageRepository.findByArticleBoissonId(boisson.getId()).ifPresent(e -> {
            if (e.getBouteillesVides() > 0 || mouvementRepository.existsByEmballageId(e.getId())) {
                throw new IllegalArgumentException("« " + boisson.getLibelle() + " » a déjà un casier (" + e.getCode() + ") et "
                    + "un historique de bouteilles vides : elle reste consignée. Une boisson sans consigne (whisky, vin, jus en "
                    + "carton…) se crée sans casier.");
            }
            emballageRepository.delete(e);
            log.info("Conditionnement {} retiré : la boisson {} devient sans consigne", e.getCode(), boisson.getCode());
        });
    }

    /**
     * Complète une requête de modification par les valeurs actuelles de
     * l'article, pour tout champ laissé vide : {@code StockService
     * .appliquerArticle} remplace tout, et un appel partiel effaçait sinon
     * les comptes de l'article (le rendant impossible à vendre ou à
     * réceptionner), son prix d'achat indicatif et son entrepôt
     * d'affectation — ces deux derniers, les écrans de ce module ne les
     * envoient même pas. La catégorie et la société, elles, restent celles
     * de la requête : les vider est un choix légitime.
     */
    private static ArticleRequest completerParLExistant(Article a, ArticleRequest req) {
        return new ArticleRequest(
            req.code(), req.libelle(), req.uniteMesure(), req.type(), req.categorie(), req.societe(),
            req.entrepotId() != null ? req.entrepotId() : (a.getEntrepot() == null ? null : a.getEntrepot().getId()),
            numeroOuExistant(req.compteStockNumero(), a.getCompteStock()),
            numeroOuExistant(req.compteChargeNumero(), a.getCompteCharge()),
            numeroOuExistant(req.compteProduitNumero(), a.getCompteProduit()),
            numeroOuExistant(req.compteAchatNumero(), a.getCompteAchat()),
            req.prixVente() != null ? req.prixVente() : a.getPrixVente(),
            req.prixAchat() != null ? req.prixAchat() : a.getPrixAchat(),
            req.minerais() != null ? req.minerais() : a.isMinerais(),
            req.soumisTva() != null ? req.soumisTva() : a.isSoumisTva(),
            req.stockMin() != null ? req.stockMin() : a.getStockMin(),
            req.actif() != null ? req.actif() : a.isActif(),
            req.sansConsigne() != null ? req.sansConsigne() : a.isSansConsigne()
        );
    }

    private static String numeroOuExistant(String numero, CompteOHADA existant) {
        if (numero != null && !numero.isBlank()) {
            return numero;
        }
        return existant == null ? null : existant.getNumero();
    }

    /**
     * Suppression definitive d'un article de la carte, reservee a
     * l'administrateur et seulement possible s'il n'a jamais ete implique
     * dans une operation reelle (vente, achat, mouvement de stock, fiche
     * technique, production, conditionnement) — sinon son historique
     * deviendrait orphelin. Pour un article deja utilise, seule la
     * desactivation (modifierArticleCarte avec actif=false) reste possible.
     *
     * <p>Libere aussi ses comptes comptables dedies (genererCompteDedie),
     * qui redeviennent disponibles pour le prochain article : seuls les
     * comptes manuels (manuel=true) sont retires, jamais un compte du
     * referentiel officiel.</p>
     */
    @PreAuthorize(ECRITURE_CARTE)
    @Transactional
    public void supprimerArticleCarte(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        exigerArticleDeLaCarte(id);

        boolean lie = venteRepository.existsLigneAvecArticle(id)
            || noteFraisRepository.existsLigneAvecArticle(id)
            || mouvementStockRepository.existsLigneAvecArticle(id)
            || ligneRecetteRepository.existsByPlatId(id)
            || ligneRecetteRepository.existsByProvisionId(id)
            || productionRepository.existsByPlatId(id)
            || productionRepository.existsLigneAvecProvision(id)
            || emballageRepository.existsByArticleBoissonId(id);
        if (lie) {
            throw new IllegalStateException(
                "Impossible de supprimer définitivement " + article.getLibelle()
                + " : il est lié à au moins une opération (vente, achat, mouvement de stock, "
                + "fiche technique, production ou conditionnement). Désactivez-le plutôt.");
        }

        stockNiveauRepository.deleteByArticleId(id);

        List<CompteOHADA> comptesDedies = new ArrayList<>();
        if (article.getCompteStock() != null) comptesDedies.add(article.getCompteStock());
        if (article.getCompteCharge() != null) comptesDedies.add(article.getCompteCharge());
        if (article.getCompteProduit() != null) comptesDedies.add(article.getCompteProduit());
        if (article.getCompteAchat() != null) comptesDedies.add(article.getCompteAchat());

        articleRepository.delete(article);
        for (CompteOHADA c : comptesDedies) {
            if (c.isManuel()) {
                compteRepository.delete(c);
            }
        }
        log.info("Article de carte supprimé définitivement [id={}, code={}]", id, article.getCode());
    }

    /** Etat du stock limite aux articles de la carte (et, pour un responsable cuisine ou bar, a son domaine). */
    @PreAuthorize(LECTURE_CARTE)
    @Transactional(readOnly = true)
    public List<StockNiveauResponse> etatStockCarte() {
        PerimetreRestaurant.Perimetre perimetre = PerimetreRestaurant.restaurantCourant();
        Set<Long> idsCarte = articleRepository.findAll().stream()
            .filter(a -> TYPES_CARTE.contains(a.getType()))
            .filter(a -> perimetre.autorise(a.getType()))
            .map(Article::getId)
            .collect(java.util.stream.Collectors.toSet());
        Map<Long, Map<Long, BigDecimal>> reserve = reservations.reservations();
        return stockService.etatStockInterne().stream()
            .filter(s -> idsCarte.contains(s.articleId()))
            .map(s -> s.avecReserve(reserve.getOrDefault(s.articleId(), Map.of()).getOrDefault(s.entrepotId(), BigDecimal.ZERO)))
            .toList();
    }

    /**
     * Lots de stock actifs des boissons (date d'achat, fournisseur, prix) —
     * suivi de gestion séparé du coût moyen pondéré ci-dessus, voir
     * {@link LotStockService}. Un plat n'a pas de lot d'achat : il ne figure
     * que sur {@link #etatStockCarte()}.
     */
    @PreAuthorize(LECTURE_BAR)
    @Transactional(readOnly = true)
    public List<LotStockResponse> listerLotsBoissons() {
        return lotStockRepository.findByArticle_TypeAndQuantiteRestanteGreaterThanOrderByDateEntreeAsc(
                TypeArticle.BOISSON, java.math.BigDecimal.ZERO)
            .stream().map(LotStockResponse::from).toList();
    }

    /** Entrepots disponibles pour une reception, sans exiger le module LOGISTIQUE. */
    @PreAuthorize(LECTURE_CARTE)
    @Transactional(readOnly = true)
    public List<EntrepotResponse> listerEntrepots() {
        return entrepotRepository.findAll().stream()
            .filter(Entrepot::isActif)
            .map(EntrepotResponse::from)
            .toList();
    }

    private void exigerTypeCarte(TypeArticle type) {
        if (type == null || !TYPES_CARTE.contains(type)) {
            throw new IllegalArgumentException(
                "Le module Restaurant ne gère que les articles de type PLAT ou BOISSON");
        }
    }

    private Article exigerArticleDeLaCarte(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (!TYPES_CARTE.contains(article.getType())) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'appartient pas à la carte du restaurant");
        }
        return article;
    }

    // ---------------------------------------------------------------------
    // Fiche technique et production
    //
    // Chaînon manquant entre les provisions (matière première) et la carte :
    // sans lui, aucun endpoint de ce module ne pouvait faire ENTRER un plat en
    // stock, et sa vente affichait une marge de 100 % faute de coût de revient.
    //
    // Une production réutilise deux primitives éprouvées de StockService
    // plutôt qu'un type de mouvement dédié : sortie des ingrédients
    // (D 6033 / C 331, au CMP) puis entrée des portions
    // (D 361 / C 736 — la production stockée SYSCOHADA). Deux pièces du
    // journal STOCK, chacune équilibrée.
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public RecetteResponse consulterRecette(Long platId) {
        Article plat = exigerPlat(platId);
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId),
            cmpParArticle());
    }

    /** Fiches de tous les plats, y compris ceux qui n'en ont pas encore (lignes vides). */
    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<RecetteResponse> listerRecettes() {
        Map<Long, List<LigneRecette>> parPlat = ligneRecetteRepository.findAllAvecArticles().stream()
            .collect(java.util.stream.Collectors.groupingBy(l -> l.getPlat().getId()));
        Map<Long, BigDecimal> cmp = cmpParArticle();
        return articleRepository.findAll().stream()
            .filter(a -> a.getType() == TypeArticle.PLAT)
            .sorted(Comparator.comparing(Article::getCode))
            .map(plat -> construireRecette(plat, parPlat.getOrDefault(plat.getId(), List.of()), cmp))
            .toList();
    }

    /**
     * Remplace la fiche entière : composer un plat en ingrédients est le
     * travail quotidien du chef cuisinier (responsable restaurant), pas de
     * l'administrateur — qui, lui, se limite à créer la fiche article
     * (code, prix, imputation comptable) sur la carte.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public RecetteResponse enregistrerRecette(Long platId, RecetteRequest req) {
        Article plat = exigerPlat(platId);
        // La durée de conservation fait partie de la fiche : elle fixe, à chaque production, la date d'expiration des portions.
        plat.setDureeConservationJours(req.dureeConservationJours());
        ligneRecetteRepository.deleteByPlatId(platId);
        // Flush avant réinsertion : la contrainte d'unicité (plat, provision)
        // se déclencherait sur une ligne recréée à l'identique si les deletes
        // partaient après les inserts.
        ligneRecetteRepository.flush();

        Set<Long> vues = new java.util.HashSet<>();
        for (LigneRecetteRequest l : req.lignes()) {
            if (!vues.add(l.provisionId())) {
                throw new IllegalArgumentException(
                    "La même provision figure deux fois dans la fiche technique : regroupez les quantités.");
            }
            Article provision = exigerProvision(l.provisionId());
            ligneRecetteRepository.save(LigneRecette.builder()
                .plat(plat).provision(provision).quantite(l.quantite()).build());
        }
        log.info("Fiche technique enregistrée [plat={}, ingrédients={}, conservation={} j]",
            plat.getCode(), req.lignes().size(), req.dureeConservationJours());
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId), cmpParArticle());
    }

    /**
     * Ajuste le prix de vente directement depuis la fiche technique : le
     * chef y voit déjà le coût de revient réel et la marge, c'est le bon
     * endroit pour corriger un prix devenu trop juste — plutôt que de
     * l'obliger à rouvrir la carte pour la même information.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public RecetteResponse modifierPrixVenteCarte(Long platId, BigDecimal prixVente) {
        Article plat = exigerPlat(platId);
        plat.setPrixVente(prixVente);
        articleRepository.save(plat);
        log.info("Prix de vente modifié [plat={}, prix={}]", plat.getCode(), prixVente);
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId), cmpParArticle());
    }

    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<ProductionResponse> listerProductions(LocalDate du, LocalDate au) {
        return productionRepository.rechercherPeriode(du, au).stream()
            .map(ProductionResponse::from)
            .toList();
    }

    /**
     * Produit des portions d'un plat en consommant des provisions.
     *
     * <p>Le coût de revient n'est pas saisi : il se déduit du coût réel des
     * ingrédients sortis (leur CMP au moment de la sortie), divisé par le
     * nombre de portions. C'est ce qui donne enfin au plat un CMP non nul.</p>
     *
     * <p>Opération du quotidien, donc ouverte au responsable restaurant —
     * contrairement à la fiche technique, qui définit l'offre.</p>
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public ProductionResponse produire(ProductionRequest req) {
        Article plat = exigerPlat(req.platId());
        // Vérifié AVANT toute écriture : sans compte de variation, l'entrée en
        // stock des portions n'aurait pas de contrepartie et la pièce partirait
        // déséquilibrée au fond de StockService, avec un message obscur.
        if (plat.getCompteCharge() == null) {
            throw new IllegalArgumentException(
                "Le plat " + plat.getCode() + " n'a pas de compte de variation des stocks (736) : "
                + "impossible de constater sa production. Complétez sa fiche dans la carte.");
        }
        if (plat.getCompteStock() == null) {
            throw new IllegalArgumentException(
                "Le plat " + plat.getCode() + " n'a pas de compte de stock (361) : "
                + "impossible de constater sa production. Complétez sa fiche dans la carte.");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        LocalDate date = req.dateProduction() == null ? LocalDate.now() : req.dateProduction();
        User auteur = currentUser.requireUser();

        // Les ingrédients sont regroupés par provision : deux lignes portant la
        // même provision produiraient deux sorties concurrentes sur le même
        // couple (article, entrepôt), donc deux lectures du même CMP.
        Map<Long, BigDecimal> quantiteParProvision = new LinkedHashMap<>();
        for (LigneProductionRequest l : req.lignes()) {
            quantiteParProvision.merge(l.provisionId(), l.quantite(), BigDecimal::add);
        }
        List<StockService.SortieVente> sorties = new ArrayList<>();
        List<Article> provisionsOrdonnees = new ArrayList<>();
        for (var e : quantiteParProvision.entrySet()) {
            Article provision = exigerProvision(e.getKey());
            provisionsOrdonnees.add(provision);
            sorties.add(new StockService.SortieVente(provision, e.getValue()));
        }

        // 1. Sortie des ingrédients, valorisée au CMP : D 6033 / C 331.
        MouvementStock sortie = stockService.enregistrerSortieVenteInterne(
            date, "Production " + plat.getLibelle(), entrepot, sorties, auteur);

        // Le coût réel n'est connu qu'ici : appliquerLigne a rempli montant et
        // coutUnitaire de chaque ligne au CMP du moment.
        BigDecimal coutTotal = sortie.getLignes().stream()
            .map(LigneMouvementStock::getMontant)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 6 décimales comme le CMP interne de StockService : arrondir ici à 2
        // ferait diverger la valeur entrée en stock du coût réellement sorti dès
        // que les portions ne divisent pas rond.
        BigDecimal coutUnitaire = coutTotal.divide(req.quantite(), 6, RoundingMode.HALF_UP);

        // 2. Entrée des portions, contrepartie 736 : D 361 / C 736.
        MouvementStock entree = stockService.enregistrerEntreeRestaurantInterne(
            date, "Production " + plat.getLibelle(), entrepot, plat.getCompteCharge(),
            plat, req.quantite(), coutUnitaire, auteur);

        Production production = Production.builder()
            .reference(referenceGenerator.pourProduction())
            .dateProduction(date)
            .plat(plat)
            .entrepot(entrepot)
            .quantite(req.quantite())
            .dateExpiration(PeremptionPlats.dateExpiration(date, plat.getDureeConservationJours()))
            .coutTotal(coutTotal)
            .coutUnitaire(coutUnitaire)
            .statut(StatutProduction.VALIDEE)
            .mouvementSortie(sortie)
            .mouvementEntree(entree)
            .createdBy(auteur)
            .build();
        for (int i = 0; i < provisionsOrdonnees.size(); i++) {
            LigneMouvementStock ligneStock = sortie.getLignes().get(i);
            production.addLigne(LigneProduction.builder()
                .provision(provisionsOrdonnees.get(i))
                .quantite(ligneStock.getQuantite())
                .coutUnitaire(ligneStock.getCoutUnitaire())
                .montant(ligneStock.getMontant())
                .build());
        }
        Production saved = productionRepository.save(production);
        log.info("Production enregistrée [ref={}, plat={}, portions={}, coût unitaire={}]",
            saved.getReference(), plat.getCode(), req.quantite(), coutUnitaire);
        return ProductionResponse.from(saved);
    }

    /** Extourne les deux mouvements : les ingrédients reviennent en stock, les portions en sortent. */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public ProductionResponse annulerProduction(Long id) {
        Production production = productionRepository.findAvecLignes(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Production", id));
        if (production.getStatut() == StatutProduction.ANNULEE) {
            throw new TransitionInvalideException(
                "La production " + production.getReference() + " est déjà annulée.");
        }
        User auteur = currentUser.requireUser();
        // L'entrée d'abord : annuler la sortie des ingrédients avant de retirer
        // les portions laisserait, entre les deux, un stock de plats sans
        // contrepartie. L'ordre inverse échoue franchement si les portions ont
        // déjà été vendues, ce qui est le comportement voulu.
        if (production.getMouvementEntree() != null) {
            stockService.annulerMouvementInterne(production.getMouvementEntree(), auteur);
        }
        if (production.getMouvementSortie() != null) {
            stockService.annulerMouvementInterne(production.getMouvementSortie(), auteur);
        }
        production.setStatut(StatutProduction.ANNULEE);
        log.info("Production annulée [ref={}]", production.getReference());
        return ProductionResponse.from(productionRepository.save(production));
    }

    /** CMP courant par article, tous entrepôts confondus — une seule passe sur l'état du stock. */
    private Map<Long, BigDecimal> cmpParArticle() {
        Map<Long, BigDecimal[]> cumul = new LinkedHashMap<>(); // [quantite, valeur]
        for (StockNiveauResponse s : stockService.etatStockInterne()) {
            cumul.merge(s.articleId(), new BigDecimal[]{s.quantite(), s.valeurTotale()},
                (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }
        Map<Long, BigDecimal> cmp = new LinkedHashMap<>();
        cumul.forEach((articleId, qv) -> cmp.put(articleId,
            // 6 décimales : voir StockNiveauResponse (coût unitaire affiché en francs).
            qv[0].signum() == 0 ? BigDecimal.ZERO : qv[1].divide(qv[0], 6, RoundingMode.HALF_UP)));
        return cmp;
    }

    private RecetteResponse construireRecette(Article plat, List<LigneRecette> lignes, Map<Long, BigDecimal> cmp) {
        List<LigneRecetteResponse> lignesDto = new ArrayList<>();
        BigDecimal coutEstime = BigDecimal.ZERO;
        for (LigneRecette l : lignes) {
            Article provision = l.getProvision();
            BigDecimal coutMoyen = cmp.getOrDefault(provision.getId(), BigDecimal.ZERO);
            BigDecimal coutLigne = coutMoyen.multiply(l.getQuantite()).setScale(2, RoundingMode.HALF_UP);
            coutEstime = coutEstime.add(coutLigne);
            lignesDto.add(new LigneRecetteResponse(
                l.getId(), provision.getId(), provision.getCode(), provision.getLibelle(),
                provision.getUniteMesure(), l.getQuantite(), coutMoyen, coutLigne));
        }
        return new RecetteResponse(
            plat.getId(), plat.getCode(), plat.getLibelle(), plat.getUniteMesure(),
            plat.getPrixVente(), lignesDto, coutEstime, plat.getDureeConservationJours());
    }

    private Article exigerPlat(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (article.getType() != TypeArticle.PLAT) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'est pas un plat : seul un plat se produit.");
        }
        return article;
    }

    private Article exigerProvision(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'est pas une provision : "
                + "seule une provision peut entrer dans la composition d'un plat.");
        }
        return article;
    }

    // ---------------------------------------------------------------------
    // Salles et tables : plan visuel, sans lien avec les ventes pour l'instant
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<SalleResponse> listerSalles() {
        List<TableRestaurant> toutesLesTables = tableRepository.findAll();
        Map<Long, List<Vente>> ventesParTable = toutesLesTables.isEmpty()
            ? Map.of()
            : venteRepository.findActivesByTableIdIn(toutesLesTables.stream().map(TableRestaurant::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(v -> v.getTable().getId()));

        // Tables regroupées par salle depuis la lecture unique ci-dessus, plutôt
        // qu'une requête de plus par salle.
        Map<Long, List<TableRestaurant>> tablesParSalle = toutesLesTables.stream()
            .sorted(Comparator.comparing(TableRestaurant::getId))
            .collect(java.util.stream.Collectors.groupingBy(t -> t.getSalle().getId()));

        return salleRepository.findAllByOrderByOrdreAscIdAsc().stream()
            .map(s -> {
                List<TableResponse> tablesDto = tablesParSalle.getOrDefault(s.getId(), List.of()).stream()
                    .map(t -> tableResponseAvecBadges(t, ventesParTable.getOrDefault(t.getId(), List.of())))
                    .toList();
                return SalleResponse.avecTables(s, tablesDto);
            })
            .toList();
    }

    /**
     * Une commande BROUILLON compte comme "en cours" (deja engagee, pas
     * encore reglee) au meme titre qu'une VALIDEE non reglee : seule une
     * ANNULEE (deja exclue par la requete) ou une VALIDEE reglee sortent du
     * compte "non payee" — c'est ce que dit {@link Vente#estReglee()}.
     */
    private TableResponse tableResponseAvecBadges(TableRestaurant t, List<Vente> ventes) {
        boolean nonPayee = ventes.stream().anyMatch(v -> !v.estReglee());
        boolean payee = !nonPayee && !ventes.isEmpty();
        return TableResponse.from(t, nonPayee, payee);
    }

    /**
     * Commandes (ventes non annulees) rattachees a une table, la plus recente
     * en premier, lignes incluses : ce module n'a pas acces a {@code
     * VenteService.consulter} (reserve a CAISSIER/COMPTABLE/DFIN/DA/DG/ADMIN,
     * pas RESP_RESTAURANT), donc cet appel doit a lui seul suffire a afficher
     * le contenu d'une commande depuis le plan de salle.
     */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<VenteResponse> listerCommandesTable(Long tableId) {
        return venteRepository.findActivesAvecDetailsByTableId(tableId).stream()
            .map(v -> VenteResponse.from(v, true))
            .toList();
    }

    @PreAuthorize(ECRITURE_SALLES)
    @Transactional
    public SalleResponse creerSalle(SalleRequest req) {
        SalleRestaurant salle = SalleRestaurant.builder()
            .nom(req.nom())
            .ordre(req.ordre() == null ? 0 : req.ordre())
            .actif(req.actif() == null || req.actif())
            .majorationPourcentage(req.majorationPourcentage() == null ? BigDecimal.ZERO : req.majorationPourcentage())
            .build();
        salle = salleRepository.save(salle);
        return SalleResponse.from(salle, List.of());
    }

    @PreAuthorize(ECRITURE_SALLES)
    @Transactional
    public SalleResponse modifierSalle(Long id, SalleRequest req) {
        SalleRestaurant salle = salleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Salle", id));
        salle.setNom(req.nom());
        if (req.ordre() != null) salle.setOrdre(req.ordre());
        if (req.actif() != null) salle.setActif(req.actif());
        if (req.majorationPourcentage() != null) salle.setMajorationPourcentage(req.majorationPourcentage());
        salle = salleRepository.save(salle);
        return SalleResponse.from(salle, tableRepository.findBySalleIdOrderByIdAsc(id));
    }

    /**
     * Supprime la salle et ses tables (ON DELETE CASCADE).
     *
     * <p>Detache d'abord TOUTES les ventes de ces tables, y compris ANNULEE
     * (contrairement a {@link #changerStatutTable}, qui exclut deliberement
     * les ventes annulees car elles ne representent plus une commande en
     * cours) : sans ce detachement, une vente annulee garderait indefiniment
     * son {@code table_id} et la suppression violerait la contrainte de cle
     * etrangere {@code ventes.table_id} (HTTP 500 opaque).</p>
     */
    @PreAuthorize(ECRITURE_SALLES)
    @Transactional
    public void supprimerSalle(Long id) {
        if (!salleRepository.existsById(id)) {
            throw RessourceIntrouvableException.of("Salle", id);
        }
        List<Long> idsTables = tableRepository.findBySalleIdOrderByIdAsc(id).stream()
            .map(TableRestaurant::getId)
            .toList();
        detacherToutesLesVentes(idsTables);
        salleRepository.deleteById(id);
    }

    /** Detache toutes les ventes (actives et annulees) des tables donnees — voir {@link #supprimerSalle}. */
    private void detacherToutesLesVentes(List<Long> idsTables) {
        if (idsTables.isEmpty()) {
            return;
        }
        venteRepository.findAllByTableIdIn(idsTables).forEach(v -> v.setTable(null));
    }

    /**
     * Remplace en une fois toutes les tables d'une salle par l'etat envoye
     * depuis l'editeur de plan : cree celles sans identifiant, met a jour
     * celles qui en portent un, supprime les tables existantes absentes de la
     * liste. Un seul appel reseau pour le bouton "Enregistrer" du plan, plutot
     * qu'une requete par table deplacee/redimensionnee/ajoutee/retiree.
     */
    @PreAuthorize(ECRITURE_SALLES)
    @Transactional
    public SalleResponse enregistrerPlan(Long salleId, PlanSalleRequest req) {
        SalleRestaurant salle = salleRepository.findById(salleId)
            .orElseThrow(() -> RessourceIntrouvableException.of("Salle", salleId));
        // Deux tables au même numéro rendaient ambigus la commande, le badge
        // payé/non payé et l'addition. Vérifié ici pour un message clair ; la
        // contrainte d'unicité (V87) le garantit en base.
        Set<String> numeros = new java.util.HashSet<>();
        for (TableRequest tr : req.tables()) {
            if (!numeros.add(tr.numero().trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "Deux tables portent le numéro « " + tr.numero().trim() + " » dans cette salle : "
                    + "donnez-leur des numéros différents.");
            }
        }

        List<TableRestaurant> existantes = tableRepository.findBySalleIdOrderByIdAsc(salleId);
        Map<Long, TableRestaurant> existantesParId = existantes.stream()
            .collect(java.util.stream.Collectors.toMap(TableRestaurant::getId, t -> t));

        Set<Long> idsRecus = req.tables().stream()
            .map(TableRequest::id)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        List<TableRestaurant> aSupprimer = existantes.stream()
            .filter(t -> !idsRecus.contains(t.getId()))
            .toList();
        // Detache d'abord les ventes (actives et annulees) de ces tables —
        // voir supprimerSalle : sans cela, une vente annulee garderait son
        // table_id et la suppression violerait la contrainte de cle etrangere.
        detacherToutesLesVentes(aSupprimer.stream().map(TableRestaurant::getId).toList());
        tableRepository.deleteAll(aSupprimer);

        List<TableRestaurant> resultat = new ArrayList<>();
        for (TableRequest tr : req.tables()) {
            TableRestaurant table;
            if (tr.id() != null) {
                table = existantesParId.get(tr.id());
                if (table == null) {
                    throw new IllegalArgumentException(
                        "La table " + tr.id() + " n'appartient pas à cette salle");
                }
            } else {
                table = new TableRestaurant();
                table.setSalle(salle);
            }
            table.setNumero(tr.numero().trim());
            table.setForme(tr.forme() == null ? FormeTable.CARRE : tr.forme());
            table.setPosX(tr.posX());
            table.setPosY(tr.posY());
            table.setLargeur(tr.largeur());
            table.setHauteur(tr.hauteur());
            if (tr.nbChaises() != null) table.setNbChaises(tr.nbChaises());
            // "occupee" n'est volontairement pas touche ici : c'est un statut
            // du quotidien (voir changerStatutTable), independant de la
            // disposition du plan.
            resultat.add(tableRepository.save(table));
        }
        return SalleResponse.from(salle, resultat);
    }

    /**
     * Bascule l'occupation d'une table, independamment du plan : action du
     * quotidien (comme une vente ou une reception), ouverte au responsable
     * restaurant et au caissier — contrairement a la disposition elle-meme
     * (ECRITURE_SALLES, reservee a l'administrateur). Une table peut aussi
     * passer occupee automatiquement (voir VenteService.creer) ; ceci reste
     * le seul chemin pour la liberer ou la reserver a la main.
     *
     * <p>Liberer une table (occupee -> false) cloture son cycle de service :
     * les ventes qui y etaient rattachees restent des pieces comptables
     * intactes (rien n'est annule ni supprime), mais ne pointent plus vers
     * la table, qui redevient vierge pour le client suivant — sans quoi le
     * badge paye/non-paye du plan continuerait indefiniment de refleter des
     * commandes deja soldees depuis longtemps. Si une commande n'est pas
     * encore payee, seul l'administrateur peut liberer quand meme (client
     * parti sans regler, erreur a corriger...) ; le caissier et le
     * responsable restaurant doivent d'abord regler l'addition.</p>
     */
    @PreAuthorize(ECRITURE_STATUT_TABLE)
    @Transactional
    public void changerStatutTable(Long tableId, boolean occupee) {
        TableRestaurant table = tableRepository.findById(tableId)
            .orElseThrow(() -> RessourceIntrouvableException.of("Table", tableId));

        if (!occupee) {
            List<Vente> ventesLiees = venteRepository.findActivesByTableIdIn(List.of(tableId));
            boolean impayee = ventesLiees.stream().anyMatch(this::estImpayee);
            User demandeur = currentUser.requireUser();
            boolean estAdmin = demandeur.getRoles().stream().anyMatch(r -> r.getNom() == RoleType.ADMIN);
            if (impayee && !estAdmin) {
                // Seul le caissier encaisse : le responsable restaurant, qui
                // peut aussi liberer une table, doit etre oriente vers lui
                // plutot que vers une action qu'il ne peut pas faire.
                boolean estCaissier = demandeur.getRoles().stream().anyMatch(r -> r.getNom() == RoleType.CAISSIER);
                throw new IllegalStateException(
                    "Impossible de libérer cette table : une commande n'est pas encore payée. "
                    + (estCaissier
                        ? "Encaissez-la ou réglez l'addition d'abord, ou demandez à un administrateur de libérer la table quand même."
                        : "Seul le caissier peut l'encaisser ou régler l'addition : adressez-vous à lui, ou demandez à un "
                          + "administrateur de libérer la table quand même."));
            }
            ventesLiees.forEach(v -> v.setTable(null));
        }

        table.setOccupee(occupee);
        tableRepository.save(table);
    }

    /** Brouillon jamais paye, ou credit valide mais dont l'addition n'a pas encore ete reglee. */
    private boolean estImpayee(Vente v) {
        if (v.getStatut() == StatutVente.BROUILLON) {
            return true;
        }
        return v.getStatut() == StatutVente.VALIDEE
            && v.getModeReglement() == ModeReglement.CREDIT
            && v.getPieceReglement() == null;
    }

    // ---------------------------------------------------------------------
    // Plats périmés : durée de conservation de la fiche technique
    //
    // Chaque production fixe la date d'expiration de ses portions (date de production + durée de conservation de la
    // fiche). Passé cette date, celles qui n'ont pas été vendues sont périmées : on les repère d'après le stock réel (voir
    // PeremptionPlats), on refuse de les vendre, et le responsable les retire du stock en une opération — une sortie
    // « Périmé » avec son écriture, exactement comme une sortie de plat saisie à la main.
    // ---------------------------------------------------------------------

    private static final java.time.format.DateTimeFormatter JOUR = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Plats périmés encore en stock, entrepôt par entrepôt : la date d'expiration de leur production est passée. */
    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<PlatPerimeResponse> listerPlatsPerimes() {
        return calculerPlatsPerimes(LocalDate.now());
    }

    private List<PlatPerimeResponse> calculerPlatsPerimes(LocalDate jour) {
        Set<Long> retraitsPerimes = new java.util.HashSet<>(sortiePlatRepository.idsMouvementsParMotif(MotifSortiePlat.PERIME));
        List<PlatPerimeResponse> perimes = new ArrayList<>();
        for (StockNiveau niveau : stockNiveauRepository.findAllWithDetails()) {
            Article plat = niveau.getArticle();
            if (plat.getType() != TypeArticle.PLAT || niveau.getQuantite().signum() <= 0) {
                continue;
            }
            List<PeremptionPlats.Part> parts = repartitionDuStock(niveau, jour, retraitsPerimes);
            // Le stock fait foi : jamais plus de portions périmées qu'il n'y en a en stock.
            BigDecimal perimee = PeremptionPlats.quantitePerimee(parts).min(niveau.getQuantite());
            if (perimee.signum() <= 0) {
                continue;
            }
            // Coût de production moyen d'une portion, à 6 décimales comme le CMP interne de StockService.
            BigDecimal coutMoyen = niveau.getValeurTotale().divide(niveau.getQuantite(), 6, RoundingMode.HALF_UP);
            List<PlatPerimeResponse.LotPerime> lots = parts.stream()
                .filter(PeremptionPlats.Part::perimee)
                .map(p -> new PlatPerimeResponse.LotPerime(
                    p.lot().reference(), p.lot().dateProduction(), p.lot().dateExpiration(), p.quantite()))
                .toList();
            perimes.add(new PlatPerimeResponse(
                plat.getId(), plat.getCode(), plat.getLibelle(), plat.getUniteMesure(),
                niveau.getEntrepot().getId(), niveau.getEntrepot().getCode(),
                perimee, coutMoyen, perimee.multiply(coutMoyen).setScale(2, RoundingMode.HALF_UP),
                lots.stream().map(PlatPerimeResponse.LotPerime::dateExpiration).min(Comparator.naturalOrder()).orElse(null),
                lots));
        }
        return perimes;
    }

    /**
     * Ce qu'il reste de chaque production d'un plat dans un entrepôt, jugé {@code jour} : l'historique du stock du plat
     * est rejoué (voir {@link PeremptionPlats}). Un mouvement annulé est ignoré, lui et sa contre-passation, qui s'annulent.
     *
     * @param retraitsPerimes mouvements de stock qui sont des retraits pour péremption, qui entament d'abord le périmé
     */
    private List<PeremptionPlats.Part> repartitionDuStock(StockNiveau niveau, LocalDate jour, Set<Long> retraitsPerimes) {
        Long platId = niveau.getArticle().getId();
        Long entrepotId = niveau.getEntrepot().getId();
        // Productions du plat dans l'entrepôt, retrouvées par le mouvement qui a fait entrer leurs portions.
        Map<Long, PeremptionPlats.LotProduit> parMouvement = new java.util.HashMap<>();
        for (Production p : productionRepository.findValideesDuPlatDansEntrepot(platId, entrepotId)) {
            if (p.getMouvementEntree() != null) {
                parMouvement.put(p.getMouvementEntree().getId(), new PeremptionPlats.LotProduit(
                    p.getReference(), p.getDateProduction(), p.getDateExpiration(), p.getQuantite()));
            }
        }
        List<PeremptionPlats.Mouvement> chronologie = new ArrayList<>();
        for (StockGrandLivre l : stockGrandLivreRepository.rechercher(
                platId, entrepotId, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31))) {
            MouvementStock m = l.getMouvement();
            if (m != null && m.getStatut() == StatutMouvement.ANNULE) {
                continue;
            }
            if (l.getQteEntree().signum() > 0) {
                PeremptionPlats.LotProduit origine = m == null ? null : parMouvement.get(m.getId());
                if (origine == null) {   // entrée sans production : d'origine inconnue, elle n'expire jamais
                    origine = new PeremptionPlats.LotProduit(null, l.getDateEcriture(), null, l.getQteEntree());
                }
                chronologie.add(new PeremptionPlats.Mouvement(
                    l.getDateEcriture(), l.getQteEntree(), BigDecimal.ZERO, origine, false));
            }
            if (l.getQteSortie().signum() > 0) {
                chronologie.add(new PeremptionPlats.Mouvement(
                    l.getDateEcriture(), BigDecimal.ZERO, l.getQteSortie(), null,
                    m != null && retraitsPerimes.contains(m.getId())));
            }
        }
        return PeremptionPlats.rejouer(chronologie, jour);
    }

    /**
     * Retire du stock les portions périmées : une sortie « Périmé » par plat et par entrepôt, avec la même écriture qu'une
     * sortie saisie à la main (D 736x / C 361x au coût moyen) et l'expiration en précision. Tout ou rien : si une sortie
     * échoue, aucune n'est enregistrée. Le retrait est au nom de l'utilisateur connecté ; la tâche planifiée utilise
     * {@link #retirerPlatsPerimesInterne} au nom de l'administrateur qui a activé le retrait automatique.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public List<SortiePlatResponse> retirerPlatsPerimes(RetraitPerimesRequest req) {
        List<SortiePlatResponse> sorties = retirerPlatsPerimesInterne(
            currentUser.requireUser(), LocalDate.now(), req.platId(), req.entrepotId(), false);
        if (sorties.isEmpty()) {
            throw new IllegalStateException("Aucun plat périmé à retirer du stock.");
        }
        return sorties;
    }

    /**
     * Plats périmés encore en stock au {@code jour} donné, sans contrôle d'accès : pour la tâche planifiée, qui n'a pas
     * d'utilisateur connecté (voir {@code PlatsPerimesAutomatiqueService}).
     */
    @Transactional(readOnly = true)
    public List<PlatPerimeResponse> platsPerimesInterne(LocalDate jour) {
        return calculerPlatsPerimes(jour);
    }

    /**
     * Retrait des plats périmés, commun au clic du responsable et à la tâche planifiée, sans contrôle d'accès : l'appelant
     * décide qui en répond ({@code auteur}) et si le retrait est automatique, ce que la précision de la sortie dit.
     *
     * <p>Un retrait à la fois : la ligne des paramètres est verrouillée jusqu'au commit. Sans cela, le responsable qui clique
     * pendant que la tâche tourne calculerait les mêmes portions périmées, et les deux retraits sortiraient deux fois la
     * même quantité du stock, entamant au passage les portions encore bonnes.</p>
     *
     * @param platId     restreint le retrait à un plat (null : tous)
     * @param entrepotId restreint le retrait à un entrepôt (null : tous)
     * @return les sorties enregistrées, vide s'il n'y avait rien de périmé
     */
    @Transactional
    public List<SortiePlatResponse> retirerPlatsPerimesInterne(User auteur, LocalDate jour, Long platId, Long entrepotId,
                                                               boolean automatique) {
        parametresRestaurantRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID);
        List<SortiePlatResponse> sorties = new ArrayList<>();
        for (PlatPerimeResponse p : calculerPlatsPerimes(jour)) {
            if ((platId != null && !platId.equals(p.platId())) || (entrepotId != null && !entrepotId.equals(p.entrepotId()))) {
                continue;
            }
            String precision = (automatique ? "Retrait automatique — expiré : " : "Expiré : ") + p.lots().stream()
                .map(l -> l.productionReference() + " (le " + l.dateExpiration().format(JOUR) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
            sorties.add(sortiePlat(new SortiePlatRequest(
                p.platId(), p.quantitePerimee(), p.entrepotId(), MotifSortiePlat.PERIME,
                precision.length() > 500 ? precision.substring(0, 499) + "…" : precision, jour), auteur));
        }
        return sorties;
    }

    /**
     * Refuse de vendre des portions périmées. À la validation d'une vente, les portions d'un plat dont la date
     * d'expiration est passée (au jour de la vente) ne se vendent plus : si la vente en demande plus que le stock encore
     * bon, elle est refusée jusqu'à ce que le responsable retire les portions périmées (Sorties de plats). Les portions
     * encore bonnes se vendent normalement, même quand d'autres sont périmées.
     */
    @Transactional(readOnly = true)
    public void exigerPlatsNonPerimesInterne(Vente vente) {
        if (vente.getEntrepot() == null) {
            return;
        }
        Map<Long, BigDecimal> demandees = new LinkedHashMap<>();
        Map<Long, Article> plats = new java.util.HashMap<>();
        for (LigneVente l : vente.getLignes()) {
            if (l.getArticle().getType() == TypeArticle.PLAT) {
                demandees.merge(l.getArticle().getId(), l.getQuantite(), BigDecimal::add);
                plats.put(l.getArticle().getId(), l.getArticle());
            }
        }
        if (demandees.isEmpty()) {
            return;
        }
        Set<Long> retraitsPerimes = new java.util.HashSet<>(sortiePlatRepository.idsMouvementsParMotif(MotifSortiePlat.PERIME));
        for (var demande : demandees.entrySet()) {
            Article plat = plats.get(demande.getKey());
            StockNiveau niveau = stockNiveauRepository.findByArticleAndEntrepot(plat, vente.getEntrepot()).orElse(null);
            if (niveau == null) {
                continue;   // aucun stock : la garde de stock insuffisant refusera la vente
            }
            List<PeremptionPlats.Part> parts = repartitionDuStock(niveau, vente.getDateVente(), retraitsPerimes);
            BigDecimal perimee = PeremptionPlats.quantitePerimee(parts).min(niveau.getQuantite());
            BigDecimal vendable = niveau.getQuantite().subtract(perimee);
            if (perimee.signum() > 0 && demande.getValue().compareTo(vendable) > 0) {
                String expirations = parts.stream().filter(PeremptionPlats.Part::perimee)
                    .map(p -> p.lot().reference() + ", périmée le " + p.lot().dateExpiration().format(JOUR))
                    .collect(java.util.stream.Collectors.joining(" ; "));
                throw new IllegalStateException(
                    "Plat périmé : " + quantite(perimee) + " portion(s) de « " + plat.getLibelle()
                    + " » ont dépassé leur date d'expiration (" + expirations + ") et ne peuvent plus être vendues. "
                    + "Il n'en reste que " + quantite(vendable) + " de vendable(s) pour " + quantite(demande.getValue())
                    + " demandée(s) : retirez les portions périmées du stock (Sorties de plats) ou réduisez la quantité.");
            }
        }
    }

    private static String quantite(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------------
    // Provisions : vivres, épices, charbon...
    //
    // Stockées et consommées en interne (préparation des plats), jamais
    // vendues — contrairement à la carte, aucun tunnel de vente ne les
    // décrémente automatiquement : les sorties (utilisation en cuisine,
    // casse, péremption) se saisissent ici, sur un seul type générique avec
    // motif libre puisqu'aucune n'a d'incidence differente sur le stock.
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<ArticleResponse> listerProvisions() {
        return articleRepository.findAllWithComptes().stream()
            .filter(a -> a.getType() == TypeArticle.PROVISION)
            .map(ArticleResponse::from)
            .toList();
    }

    /**
     * Racines sous lesquelles chaque nouvelle provision recoit ses propres
     * comptes dedies (achat, stock, charge) — voir {@link #genererCompteDedie}.
     * Partager le compte generique de la racine entre toutes les provisions
     * rendrait le grand livre illisible par provision (impossible de savoir
     * combien a coute le riz plutot que le sel sans depouiller chaque piece).
     *
     * <p>Une provision (vivres, epices, charbon) n'est PAS une marchandise
     * revendue : elle a donc sa propre famille de comptes — 6021 "Achats de
     * matieres premieres et fournitures liees" et 6032 sa variation de stock
     * appariee — distincte de {@link #RACINE_COMPTE_ACHAT_MARCHANDISE} (6011)
     * partagee entre plats et boissons. Melanger les deux polluait la marge
     * commerciale SYSCOHADA (701 − 601 ± 6031) de tous les achats de cuisine,
     * qui n'ont rien de marchandises revendues.</p>
     */
    private static final String RACINE_COMPTE_ACHAT_MARCHANDISE = "6011";
    private static final String RACINE_COMPTE_ACHAT_PROVISION = "6021";
    private static final String RACINE_COMPTE_STOCK_PROVISION = "331";
    private static final String RACINE_COMPTE_CHARGE_PROVISION = "6032";

    /**
     * Code proposé pour une nouvelle provision, déduit de son libellé (préfixe {@code PROV}, voir
     * {@link GenerateurCodeArticle}) et libre au moment de l'appel : l'écran de création l'affiche au fil
     * de la saisie. Il est à nouveau demandé juste avant l'enregistrement, et l'unicité reste contrôlée à
     * la création.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional(readOnly = true)
    public CodeArticleSuggereResponse suggererCodeProvision(String libelle) {
        return new CodeArticleSuggereResponse(
            GenerateurCodeArticle.genererProvision(libelle, articleRepository::existsByCode));
    }

    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public ArticleResponse creerProvision(ArticleRequest req) {
        exigerTypeProvision(req.type());
        ArticleRequest reqAvecComptes = new ArticleRequest(
            req.code(), req.libelle(), req.uniteMesure(), req.type(), req.categorie(), req.societe(), req.entrepotId(),
            genererCompteDedie(RACINE_COMPTE_STOCK_PROVISION, RoleCompteArticle.STOCK, req).getNumero(),
            genererCompteDedie(RACINE_COMPTE_CHARGE_PROVISION, RoleCompteArticle.VARIATION, req).getNumero(),
            req.compteProduitNumero(),
            genererCompteDedie(RACINE_COMPTE_ACHAT_PROVISION, RoleCompteArticle.ACHAT, req).getNumero(),
            req.prixVente(), req.prixAchat(), req.minerais(), req.soumisTva(), req.stockMin(), req.actif(), null
        );
        ArticleResponse cree = stockService.creerArticleInterne(reqAvecComptes);
        log.info("Provision créée [code={}]", cree.code());
        return cree;
    }

    /**
     * Trouve le premier sous-compte disponible sous une racine (par
     * concatenation numerique : 6011 -> 60111, 60112...) et le cree pour cet
     * article de carte ou cette provision, avec le libelle de la racine
     * (tronque avant un eventuel " : " de precision, ex. "dans la Région",
     * puis depouille d'un suffixe de depot type " A1" ou " A" — ex. racine
     * "3111" "Marchandises A1" -> prefixe "Marchandises", ce "A1" identifiant
     * le depot de la racine et n'ayant pas de sens sur le compte de l'article)
     * suivi du libelle de l'article — ex. racine "6011" "Achats de
     * marchandises : dans la Région" + "Ciboule" -> "60111" "Achats de
     * marchandises : Ciboule". Chaque plat, boisson ou provision recoit ainsi
     * ses propres comptes plutot que de partager ceux de la racine entre
     * tous les articles — sans quoi le grand livre serait illisible article
     * par article.
     *
     * <p>Numerotation deliberement differente de la convention
     * "parent.suffixe" d'{@code AdminService.ajouterCompte} (reservee aux
     * comptes ajoutes depuis l'ecran Plan comptable) : ce compte doit rester
     * un compte de saisie ordinaire, indiscernable d'un compte du referentiel
     * officiel sur le reste de l'application.</p>
     */
    private CompteOHADA genererCompteDedie(String racineNumero, RoleCompteArticle role, ArticleRequest article) {
        String libelleArticle = article.libelle();
        CompteOHADA racine = compteRepository.findByNumero(racineNumero)
            .orElseThrow(() -> new IllegalStateException("Compte racine introuvable : " + racineNumero));
        for (int suffixe = 1; suffixe <= 99; suffixe++) {
            String numero = racineNumero + suffixe;
            if (compteRepository.existsByNumero(numero)) {
                continue;
            }
            String prefixeLibelle = racine.getLibelle().split(" : ")[0]
                .replaceAll("\\s+[A-Z]\\d*$", "");
            CompteOHADA nouveau = CompteOHADA.builder()
                .numero(numero)
                .libelle(prefixeLibelle + " : " + libelleArticle)
                .type(racine.getType())
                .classe(racine.getClasse())
                .parent(racine)
                .manuel(true)
                .imputable(true)
                .actif(true)
                .build();
            // Fiche du compte : son rôle pour cet article (stock, variation, ventes,
            // achats), son fonctionnement, ses contrôles et son origine.
            documentation.documenterCompteArticle(nouveau, racine, role, article.type(), article.code(), libelleArticle);
            return compteRepository.save(nouveau);
        }
        throw new IllegalStateException(
            "Plus de sous-compte disponible sous " + racineNumero + " (99 atteints)");
    }

    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public ArticleResponse modifierProvision(Long id, ArticleRequest req) {
        exigerTypeProvision(req.type());
        Article existant = exigerArticleProvision(id);
        return stockService.modifierArticleInterne(id, completerParLExistant(existant, req));
    }

    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<StockNiveauResponse> etatStockProvisions() {
        Set<Long> idsProvisions = articleRepository.findAll().stream()
            .filter(a -> a.getType() == TypeArticle.PROVISION)
            .map(Article::getId)
            .collect(java.util.stream.Collectors.toSet());
        return stockService.etatStockInterne().stream()
            .filter(s -> idsProvisions.contains(s.articleId()))
            .toList();
    }

    /** Lots de stock actifs des provisions (date d'achat, fournisseur, prix) — voir {@link #listerLotsBoissons()}. */
    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<LotStockResponse> listerLotsProvisions() {
        return lotStockRepository.findByArticle_TypeAndQuantiteRestanteGreaterThanOrderByDateEntreeAsc(
                TypeArticle.PROVISION, java.math.BigDecimal.ZERO)
            .stream().map(LotStockResponse::from).toList();
    }

    /**
     * Historique des provisions — et d'elles seules : le grand livre de stock
     * est commun à tous les articles, et cet écran affichait aussi les
     * boissons, les plats et les marchandises de la Logistique.
     *
     * <p>Chaque ligne indique si l'écran peut annuler son mouvement (voir
     * {@link #annulerMouvementProvision}) : le bouton n'est proposé que là où
     * le serveur l'accepte.</p>
     */
    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<MouvementProvisionResponse> grandLivreProvisions(
            Long articleId, Long entrepotId, LocalDate du, LocalDate au) {
        List<StockGrandLivre> lignes = stockGrandLivreRepository.rechercher(articleId, entrepotId,
                du != null ? du : LocalDate.of(2000, 1, 1), au != null ? au : LocalDate.now()).stream()
            .filter(s -> s.getArticle().getType() == TypeArticle.PROVISION)
            .toList();
        Set<Long> idsMouvements = lignes.stream()
            .map(StockGrandLivre::getMouvement)
            .filter(java.util.Objects::nonNull)
            .map(MouvementStock::getId)
            .collect(java.util.stream.Collectors.toSet());
        // Une seule requête par opération propriétaire, pas une par ligne.
        Set<Long> appartenantAUneOperation = new java.util.HashSet<>();
        if (!idsMouvements.isEmpty()) {
            appartenantAUneOperation.addAll(productionRepository.idsMouvementsSortieParmi(idsMouvements));
            appartenantAUneOperation.addAll(productionRepository.idsMouvementsEntreeParmi(idsMouvements));
            appartenantAUneOperation.addAll(venteRepository.idsMouvementsParmi(idsMouvements));
        }
        // Type et observation des sorties saisies ici, en une seule requête (une sortie plus ancienne n'en a pas).
        Map<Long, SortieProvision> sortiesParMouvement = idsMouvements.isEmpty() ? Map.of()
            : sortieProvisionRepository.findByMouvementStockIds(idsMouvements).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getMouvementStock().getId(), s -> s));
        return lignes.stream()
            .map(s -> {
                SortieProvision sortie = s.getMouvement() == null ? null : sortiesParMouvement.get(s.getMouvement().getId());
                return MouvementProvisionResponse.from(
                    StockGrandLivreResponse.from(s,
                        mouvementProvisionAnnulable(s.getMouvement(), appartenantAUneOperation)),
                    sortie == null ? null : sortie.getTypeSortie().name(),
                    sortie == null ? null : sortie.getObservation());
            })
            .toList();
    }

    /**
     * Mouvement de provision que ce module peut annuler seul : une réception
     * directe (elle porte sa pièce d'achat) ou une sortie saisie ici. Pas une
     * entrée issue du paiement d'une note de frais — le paiement resterait
     * sans marchandise —, ni un mouvement qui appartient à une production.
     */
    private static boolean mouvementProvisionAnnulable(MouvementStock m, Set<Long> appartenantAUneOperation) {
        if (m == null || m.getStatut() != StatutMouvement.VALIDE || appartenantAUneOperation.contains(m.getId())) {
            return false;
        }
        return switch (m.getType()) {
            case ENTREE -> m.getPieceAchat() != null;
            case SORTIE -> true;
            case TRANSFERT -> false;
        };
    }

    /**
     * Annule une sortie de provision saisie par erreur — ou une ancienne
     * réception directe, que ce module ne propose plus (les entrées passent
     * par l'achat) : le stock est rétabli, la pièce de stock et, pour une
     * réception, la pièce d'achat sont extournées. Sans cela, une erreur de
     * saisie (10 kg sortis au lieu de 1) n'avait plus aucune voie de
     * correction : la Logistique refuse ces articles et la Comptabilité
     * refuse d'extourner seule une pièce de stock.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public void annulerMouvementProvision(Long mouvementId) {
        MouvementStock m = mouvementStockRepository.findWithLignesById(mouvementId)
            .orElseThrow(() -> RessourceIntrouvableException.of("MouvementStock", mouvementId));
        if (m.getStatut() != StatutMouvement.VALIDE) {
            throw new TransitionInvalideException("Le mouvement " + m.getReference() + " est déjà annulé.");
        }
        if (m.getLignes().stream().anyMatch(l -> l.getArticle().getType() != TypeArticle.PROVISION)) {
            throw new IllegalArgumentException(
                "Le mouvement " + m.getReference() + " ne porte pas sur des provisions : "
                + "il ne s'annule pas depuis cet écran.");
        }
        stockService.exigerAnnulableSeul(m);
        if (m.getType() == TypeMouvementStock.ENTREE && m.getPieceAchat() == null) {
            throw new IllegalStateException(
                "L'entrée " + m.getReference() + " provient du paiement d'une note de frais : l'annuler seule "
                + "laisserait le paiement sans marchandise. Seul l'administrateur peut la corriger, "
                + "depuis la Logistique.");
        }
        if (m.getType() == TypeMouvementStock.TRANSFERT) {
            throw new IllegalArgumentException(
                "Le transfert " + m.getReference() + " s'annule depuis la Logistique.");
        }
        stockService.annulerMouvementInterne(m, currentUser.requireUser());
        log.info("Mouvement de provision annulé [ref={}, type={}]", m.getReference(), m.getType());
    }

    /**
     * Tableau de bord des provisions sur une période : stock actuel, achats,
     * consommation (utilisation cuisine, casse, péremption confondues) et
     * classement des provisions les plus consommées.
     *
     * <p>Pas de "profit" ici : une provision est un centre de coût, jamais
     * revendue — contrairement au tableau de bord de la carte.</p>
     */
    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public TableauBordProvisionsResponse tableauBordProvisions(LocalDate du, LocalDate au) {
        List<StockNiveauResponse> niveaux = etatStockProvisions();
        Map<Long, StockNiveauResponse> stockParArticle = new LinkedHashMap<>();
        BigDecimal valeurStockActuel = BigDecimal.ZERO;
        int nbSousSeuil = 0;
        for (StockNiveauResponse s : niveaux) {
            stockParArticle.put(s.articleId(), s);
            valeurStockActuel = valeurStockActuel.add(s.valeurTotale());
            if (s.sousSeuil()) nbSousSeuil++;
        }

        Map<Long, BigDecimal[]> achatParArticle = new LinkedHashMap<>(); // [quantite, montant]
        Map<LocalDate, BigDecimal> entreeParJour = new TreeMap<>();
        BigDecimal quantiteAchetee = BigDecimal.ZERO, montantAchats = BigDecimal.ZERO;
        Set<Long> receptionsDistinctes = new java.util.HashSet<>();
        for (MouvementStock m : mouvementStockRepository.receptionsProvisionsPeriode(du, au)) {
            receptionsDistinctes.add(m.getId());
            for (LigneMouvementStock l : m.getLignes()) {
                if (l.getArticle().getType() != TypeArticle.PROVISION) continue;
                quantiteAchetee = quantiteAchetee.add(l.getQuantite());
                montantAchats = montantAchats.add(l.getMontant());
                achatParArticle.merge(l.getArticle().getId(), new BigDecimal[]{l.getQuantite(), l.getMontant()},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
                entreeParJour.merge(m.getDateMouvement(), l.getQuantite(), BigDecimal::add);
            }
        }

        Map<Long, BigDecimal[]> consoParArticle = new LinkedHashMap<>(); // [quantite, montant]
        Map<LocalDate, BigDecimal> sortieParJour = new TreeMap<>();
        BigDecimal quantiteConsommee = BigDecimal.ZERO, montantConsomme = BigDecimal.ZERO;
        Set<Long> sortiesDistinctes = new java.util.HashSet<>();
        for (MouvementStock m : mouvementStockRepository.sortiesProvisionsPeriode(du, au)) {
            sortiesDistinctes.add(m.getId());
            for (LigneMouvementStock l : m.getLignes()) {
                if (l.getArticle().getType() != TypeArticle.PROVISION) continue;
                quantiteConsommee = quantiteConsommee.add(l.getQuantite());
                montantConsomme = montantConsomme.add(l.getMontant());
                consoParArticle.merge(l.getArticle().getId(), new BigDecimal[]{l.getQuantite(), l.getMontant()},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
                sortieParJour.merge(m.getDateMouvement(), l.getQuantite(), BigDecimal::add);
            }
        }

        List<ProvisionStatResponse> parProvision = new ArrayList<>();
        for (Article a : articleRepository.findAll()) {
            if (a.getType() != TypeArticle.PROVISION) continue;
            BigDecimal[] achat = achatParArticle.getOrDefault(a.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] conso = consoParArticle.getOrDefault(a.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            StockNiveauResponse stock = stockParArticle.get(a.getId());
            parProvision.add(new ProvisionStatResponse(
                a.getId(), a.getCode(), a.getLibelle(), a.getUniteMesure(),
                achat[0], conso[0],
                stock == null ? BigDecimal.ZERO : stock.quantite(),
                stock == null ? BigDecimal.ZERO : stock.valeurTotale(),
                stock != null && stock.sousSeuil()
            ));
        }
        List<ProvisionStatResponse> topConsommees = parProvision.stream()
            .filter(p -> p.quantiteConsommee().signum() > 0)
            .sorted(Comparator.comparing(ProvisionStatResponse::quantiteConsommee).reversed())
            .limit(5)
            .toList();

        Map<LocalDate, BigDecimal[]> parJour = new TreeMap<>();
        entreeParJour.forEach((d, q) -> parJour.computeIfAbsent(d, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[0] = q);
        sortieParJour.forEach((d, q) -> parJour.computeIfAbsent(d, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[1] = q);
        List<MouvementJourResponse> mouvementsParJour = parJour.entrySet().stream()
            .map(e -> new MouvementJourResponse(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList();

        return new TableauBordProvisionsResponse(
            du, au,
            niveaux.size(), valeurStockActuel, nbSousSeuil,
            quantiteAchetee, montantAchats, receptionsDistinctes.size(),
            quantiteConsommee, montantConsomme, sortiesDistinctes.size(),
            topConsommees, parProvision, mouvementsParJour
        );
    }

    private void exigerTypeProvision(TypeArticle type) {
        if (type != TypeArticle.PROVISION) {
            throw new IllegalArgumentException(
                "Cet écran ne gère que les articles de type PROVISION (vivres, épices, charbon...)");
        }
    }

    private Article exigerArticleProvision(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'est pas une provision");
        }
        return article;
    }

    /**
     * Sortie d'une provision : utilisation en cuisine, casse, péremption, repas du personnel... Le type se
     * choisit dans une liste fermée ({@link TypeSortieProvision}) et l'observation, libre, en donne le détail
     * (obligatoire pour « Autre », sans quoi la sortie resterait inexpliquée). Un seul mécanisme, le type n'a
     * pas d'incidence sur le traitement : sortie de stock au CMP avec pièce comptable, même mécanique qu'une
     * vente (voir {@code StockService.enregistrerSortieVenteInterne}) ; la garde de stock insuffisant s'applique
     * ici aussi. Le type et l'observation sont conservés avec la sortie, pour l'historique.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public void enregistrerSortieProvision(ProvisionSortieRequest req) {
        Article article = exigerArticleProvision(req.articleId());
        String observation = req.observation() == null || req.observation().isBlank() ? null : req.observation().trim();
        if (req.typeSortie() == TypeSortieProvision.AUTRE && observation == null) {
            throw new IllegalArgumentException(
                "Précisez la raison de la sortie dans l'observation : le type « Autre » ne l'explique pas.");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        LocalDate date = req.dateMouvement() == null ? LocalDate.now() : req.dateMouvement();
        User auteur = currentUser.requireUser();
        String libelle = "Sortie provision — " + req.typeSortie().libelle() + " — " + article.getLibelle()
            + (observation != null ? " (" + observation + ")" : "");

        MouvementStock sortie = stockService.enregistrerSortieVenteInterne(date, libelleMouvement(libelle), entrepot,
            List.of(new StockService.SortieVente(article, req.quantite())), auteur);
        sortieProvisionRepository.save(SortieProvision.builder()
            .mouvementStock(sortie)
            .typeSortie(req.typeSortie())
            .observation(observation)
            .createdBy(auteur)
            .build());
        log.info("Sortie de provision enregistrée [provision={}, qte={}, type={}, mouvement={}]",
            article.getCode(), req.quantite(), req.typeSortie(), sortie.getReference());
    }

    /**
     * Le libellé d'un mouvement de stock est repris par sa pièce et ses
     * écritures, trois colonnes de 255 caractères : un motif saisi plus long
     * (jusqu'à 500) faisait échouer l'enregistrement. Tronqué ici, le motif
     * reste entier là où il est conservé (sortie de plat, mouvement de vides).
     */
    private static String libelleMouvement(String libelle) {
        return libelle.length() <= 255 ? libelle : libelle.substring(0, 254) + "…";
    }

    // ---------------------------------------------------------------------
    // Sorties de plats hors vente (périmé, moisi, renversé, offert...)
    // ---------------------------------------------------------------------

    /**
     * Sortie de stock d'un plat qui ne sera pas vendu : périmé, moisi,
     * renversé, brûlé, offert, repas du personnel...
     *
     * <p><b>Une sortie par la même mécanique qu'une vente, un compte par motif.</b>
     * Le plat quitte le stock au coût de production moyen, au journal STOCK :
     * D compte du motif / C 361x stock du plat. La production stockée (736x)
     * n'est plus soldée par la sortie, si bien que le coût des ingrédients
     * consommés reste en charge — la perte apparaît au résultat, sous son nom.
     * Le compte débité est celui du motif ({@link #compteSortiePlat}) : 736.1
     * à 736.5 pour un plat périmé, moisi, renversé, brûlé ou « autre » (variation
     * des stocks de produits finis, à part de la sortie des plats vendus),
     * 6276.2 pour un plat offert, 6617.1 pour un repas du personnel. Même
     * principe que les boissons offertes (6276.1) et périmées (6031.2).</p>
     *
     * <p>Un plat au coût de production nul (entré sans coût) sort sans
     * écriture : il n'y a rien à constater.</p>
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public SortiePlatResponse enregistrerSortiePlat(SortiePlatRequest req) {
        Article plat = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        reservations.exigerDisponible(plat, entrepot, req.quantite(), null);
        return sortiePlat(req, currentUser.requireUser());
    }

    /** La sortie de plat, au nom de {@code auteur} : l'utilisateur connecté, ou l'administrateur du retrait automatique. */
    private SortiePlatResponse sortiePlat(SortiePlatRequest req, User auteur) {
        Article plat = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        if (plat.getType() != TypeArticle.PLAT) {
            throw new IllegalArgumentException("L'article " + plat.getCode() + " n'est pas un plat : "
                + "une boisson perdue se déclare depuis les mouvements de bouteilles, une provision depuis ses entrées / sorties.");
        }
        String precision = req.precision() == null || req.precision().isBlank() ? null : req.precision().trim();
        if (req.motif() == MotifSortiePlat.AUTRE && precision == null) {
            throw new IllegalArgumentException("Précisez la raison de la sortie : le motif « Autre » ne l'explique pas.");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        LocalDate date = req.dateSortie() == null ? LocalDate.now() : req.dateSortie();

        // Chaque motif a son compte (voir compteSortiePlat) : seule la sortie d'un plat vendu passe par le compte de variation du plat.
        CompteOHADA compteCharge = compteDuMotif(req.motif());

        String libelle = "Sortie de plat — " + req.motif().libelle() + " — " + plat.getLibelle()
            + (precision != null ? " (" + precision + ")" : "");
        MouvementStock mouvement = stockService.enregistrerSortieVenteInterne(date, libelleMouvement(libelle), entrepot,
            List.of(new StockService.SortieVente(plat, req.quantite(), null, compteCharge)), auteur);
        BigDecimal valeur = mouvement.getLignes().stream()
            .map(LigneMouvementStock::getMontant)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        SortiePlat sortie = sortiePlatRepository.save(SortiePlat.builder()
            .article(plat)
            .entrepot(entrepot)
            .mouvementStock(mouvement)
            .quantite(req.quantite())
            .motif(req.motif())
            .precisionMotif(precision)
            .dateSortie(date)
            .valeur(valeur)
            .createdBy(auteur)
            .build());
        log.info("Sortie de plat enregistrée [plat={}, qte={}, motif={}, valeur={}, mouvement={}]",
            plat.getCode(), req.quantite(), req.motif(), valeur, mouvement.getReference());
        return SortiePlatResponse.from(sortie);
    }

    @PreAuthorize(LECTURE_CUISINE)
    @Transactional(readOnly = true)
    public List<SortiePlatResponse> listerSortiesPlats(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.of(2999, 12, 31);
        return sortiePlatRepository.rechercher(debut, fin).stream().map(SortiePlatResponse::from).toList();
    }

    /**
     * Annule une sortie de plat saisie par erreur : les portions reviennent en
     * stock au coût auquel elles étaient sorties, et la pièce est extournée.
     * La sortie reste dans l'historique, marquée annulée.
     */
    @PreAuthorize(ECRITURE_CUISINE)
    @Transactional
    public SortiePlatResponse annulerSortiePlat(Long id) {
        SortiePlat sortie = sortiePlatRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("SortiePlat", id));
        if (sortie.isAnnulee()) {
            throw new TransitionInvalideException("Cette sortie de plat est déjà annulée.");
        }
        User auteur = currentUser.requireUser();
        stockService.annulerMouvementInterne(sortie.getMouvementStock(), auteur);
        sortie.setAnnulee(true);
        sortie.setAnnuleeLe(java.time.Instant.now());
        sortie.setAnnuleePar(auteur);
        log.info("Sortie de plat annulée [id={}, mouvement={}]", id, sortie.getMouvementStock().getReference());
        return SortiePlatResponse.from(sortie);
    }

    // ---------------------------------------------------------------------
    // Pertes des boissons sans consigne (V134)
    // ---------------------------------------------------------------------

    /**
     * Perte d'une boisson sans consigne (casse, péremption, cadeau) : la boisson sort du stock au coût moyen, sur le compte
     * de la même perte d'une boisson consignée (casse : compte de la boisson ; périmée : 6031.2 ; offerte : 6276.1). Aucun
     * vide : ces boissons n'en ont pas. Une boisson consignée se déclare, elle, par les mouvements de vides (sa bouteille
     * suit). Ce que des commandes en attente ont mis de côté ne se perd pas.
     */
    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public SortieBoissonResponse enregistrerSortieBoisson(SortieBoissonRequest req) {
        Article boisson = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        if (boisson.getType() != TypeArticle.BOISSON) {
            throw new IllegalArgumentException("L'article " + boisson.getCode() + " n'est pas une boisson.");
        }
        if (!boisson.isSansConsigne()) {
            throw new IllegalArgumentException("« " + boisson.getLibelle() + " » est une boisson consignée : déclarez sa perte "
                + "par un mouvement de bouteilles vides (casse d'une pleine, périmée, offerte), qui gère aussi sa bouteille.");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        reservations.exigerDisponible(boisson, entrepot, req.quantite(), null);
        User auteur = currentUser.requireUser();
        LocalDate date = req.dateSortie() == null ? LocalDate.now() : req.dateSortie();
        String precision = req.precision() == null || req.precision().isBlank() ? null : req.precision().trim();
        String libelle = req.motif().libelle() + " — " + boisson.getLibelle() + (precision != null ? " (" + precision + ")" : "");
        MouvementStock mouvement = stockService.enregistrerSortieVenteInterne(date, libelleMouvement(libelle), entrepot,
            List.of(new StockService.SortieVente(boisson, req.quantite(), null, comptePerte(req.motif().equivalentConsigne()))),
            auteur);
        BigDecimal valeur = mouvement.getLignes().stream()
            .map(LigneMouvementStock::getMontant)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        SortieBoisson sortie = sortieBoissonRepository.save(SortieBoisson.builder()
            .article(boisson)
            .entrepot(entrepot)
            .mouvementStock(mouvement)
            .quantite(req.quantite())
            .motif(req.motif())
            .precisionMotif(precision)
            .dateSortie(date)
            .valeur(valeur)
            .createdBy(auteur)
            .build());
        log.info("Perte de boisson sans consigne enregistrée [boisson={}, qte={}, motif={}, valeur={}, mouvement={}]",
            boisson.getCode(), req.quantite(), req.motif(), valeur, mouvement.getReference());
        return SortieBoissonResponse.from(sortie);
    }

    @PreAuthorize(LECTURE_BAR)
    @Transactional(readOnly = true)
    public List<SortieBoissonResponse> listerSortiesBoissons(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.of(2999, 12, 31);
        return sortieBoissonRepository.rechercher(debut, fin).stream().map(SortieBoissonResponse::from).toList();
    }

    /** Annule une perte saisie par erreur : la boisson revient en stock au coût auquel elle était sortie, pièce extournée. */
    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public SortieBoissonResponse annulerSortieBoisson(Long id) {
        SortieBoisson sortie = sortieBoissonRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("SortieBoisson", id));
        if (sortie.isAnnulee()) {
            throw new TransitionInvalideException("Cette perte de boisson est déjà annulée.");
        }
        User auteur = currentUser.requireUser();
        stockService.annulerMouvementInterne(sortie.getMouvementStock(), auteur);
        sortie.setAnnulee(true);
        sortie.setAnnuleeLe(java.time.Instant.now());
        sortie.setAnnuleePar(auteur);
        log.info("Perte de boisson annulée [id={}, mouvement={}]", id, sortie.getMouvementStock().getReference());
        return SortieBoissonResponse.from(sortie);
    }

    // ---------------------------------------------------------------------
    // Emballages : conditionnement et stock de vides
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE_BAR)
    @Transactional(readOnly = true)
    public List<EmballageResponse> listerEmballages() {
        return emballageRepository.findAllAvecBoisson().stream().map(EmballageResponse::from).toList();
    }

    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public EmballageResponse creerEmballage(EmballageRequest req) {
        if (emballageRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un emballage avec le code " + req.code() + " existe déjà");
        }
        if (emballageRepository.existsByArticleBoissonId(req.articleBoissonId())) {
            throw new IllegalArgumentException(
                "Cette boisson a déjà un conditionnement : une boisson ne peut en avoir qu'un seul");
        }
        EmballageBoisson e = new EmballageBoisson();
        appliquerEmballage(e, req);
        return EmballageResponse.from(emballageRepository.save(e));
    }

    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public EmballageResponse modifierEmballage(Long id, EmballageRequest req) {
        EmballageBoisson e = chargerEmballage(id);
        if (!e.getCode().equals(req.code()) && emballageRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un emballage avec le code " + req.code() + " existe déjà");
        }
        if (!e.getArticleBoisson().getId().equals(req.articleBoissonId())
            && emballageRepository.existsByArticleBoissonId(req.articleBoissonId())) {
            throw new IllegalArgumentException("Cette boisson a déjà un conditionnement");
        }
        appliquerEmballage(e, req);
        return EmballageResponse.from(emballageRepository.save(e));
    }

    /**
     * Le stock de vides n'est volontairement PAS repris de la requete : il
     * n'appartient qu'aux mouvements, sans quoi l'historique cesserait
     * d'expliquer l'etat courant.
     */
    private void appliquerEmballage(EmballageBoisson e, EmballageRequest req) {
        Article boisson = articleRepository.findById(req.articleBoissonId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleBoissonId()));
        if (boisson.getType() != TypeArticle.BOISSON) {
            throw new IllegalArgumentException(
                "L'article " + boisson.getCode() + " n'est pas une boisson : il ne peut pas porter un conditionnement");
        }
        if (boisson.isSansConsigne()) {
            throw new IllegalArgumentException("« " + boisson.getLibelle() + " » est une boisson sans consigne : elle n'a pas "
                + "de casier ni de bouteilles vides. Si ses bouteilles doivent être gérées, rendez-la « consignée » sur sa fiche "
                + "(Carte) avant de créer son casier.");
        }
        e.setCode(req.code());
        e.setLibelle(req.libelle());
        e.setFormat(req.format());
        e.setArticleBoisson(boisson);
        e.setContenanceCasier(req.contenanceCasier());
        e.setActif(req.actif() == null || req.actif());
    }

    // ---------------------------------------------------------------------
    // Mouvements du stock de vides
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE_BAR)
    @Transactional(readOnly = true)
    public List<MouvementEmballageResponse> listerMouvements(Long emballageId, LocalDate du, LocalDate au) {
        return mouvementRepository.rechercher(emballageId, du, au).stream()
            .map(MouvementEmballageResponse::from)
            .toList();
    }

    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public MouvementEmballageResponse enregistrerMouvement(MouvementEmballageRequest req) {
        // Les mouvements automatiques ne se saisissent pas a la main : les
        // laisser passer permettrait de gonfler le stock sans vente reelle.
        if (req.type() == TypeMouvementEmballage.VENTE || req.type() == TypeMouvementEmballage.RETOUR_VENTE) {
            throw new IllegalArgumentException(
                "Les mouvements de vente sont générés automatiquement à la validation d'une vente");
        }
        // Un ajustement corrige arbitrairement le compteur de vides (contrairement
        // a une casse ou une peremption, qui documentent un evenement reel) :
        // reserve a l'administrateur pour eviter qu'un ecart soit maquille en
        // ajustement plutot que reellement explique.
        if (req.type() == TypeMouvementEmballage.AJUSTEMENT_PLUS || req.type() == TypeMouvementEmballage.AJUSTEMENT_MOINS) {
            boolean estAdmin = currentUser.requireUser().getRoles().stream()
                .anyMatch(r -> r.getNom() == RoleType.ADMIN);
            if (!estAdmin) {
                throw new AccessDeniedException("Seul un administrateur peut saisir un ajustement d'inventaire");
            }
        }
        EmballageBoisson e = chargerEmballage(req.emballageId());
        User auteur = currentUser.requireUser();

        MouvementEmballage m = appliquerEtJournaliser(
            e, req.type(), req.quantite(), req.dateMouvement(), req.motif(), null, auteur);

        // CASSE_PLEINE, PERIME et CADEAU font aussi perdre la boisson
        // elle-meme (pas seulement le contenant) : sortie de stock au CMP,
        // credit du compte de stock de la boisson — casse, peremption et
        // offre gracieuse sont toutes des pertes de marchandise reelle, pas de
        // simples ajustements d'emballage. Le compte debite depend de la perte
        // (voir compteChargePerte) : la casse garde celui de la boisson, comme
        // une vente ; la boisson perimee va en 6031.2, la boisson offerte en 6276.1.
        if (req.type() == TypeMouvementEmballage.CASSE_PLEINE || req.type() == TypeMouvementEmballage.PERIME
            || req.type() == TypeMouvementEmballage.CADEAU) {
            if (req.entrepotId() == null) {
                throw new IllegalArgumentException(
                    "L'entrepôt est obligatoire pour enregistrer la perte d'une boisson");
            }
            Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
            reservations.exigerDisponible(e.getArticleBoisson(), entrepot, BigDecimal.valueOf(req.quantite()), null);
            CompteOHADA compteCharge = comptePerte(req.type());
            String motifLibelle = switch (req.type()) {
                case PERIME -> "Boisson périmée";
                case CADEAU -> "Boisson offerte (cadeau)";
                default -> "Casse (bouteille pleine)";
            };
            String libelle = motifLibelle + " — " + e.getArticleBoisson().getLibelle()
                + (req.motif() != null && !req.motif().isBlank() ? " (" + req.motif() + ")" : "");
            MouvementStock sortie = stockService.enregistrerSortieVenteInterne(
                m.getDateMouvement(), libelleMouvement(libelle), entrepot,
                List.of(new StockService.SortieVente(
                    e.getArticleBoisson(), BigDecimal.valueOf(req.quantite()), null, compteCharge)),
                auteur);
            // Retrouvée à l'annulation de la perte, pour rétablir le stock.
            m.setMouvementStock(sortie);
        }

        return MouvementEmballageResponse.from(m);
    }

    /**
     * Compte débité par la sortie de stock d'une boisson perdue : 6276.1 pour une boisson offerte, 6031.2 pour une boisson
     * périmée ; {@code null} pour une casse, qui garde le compte de variation de la boisson, comme une vente.
     */
    static String compteChargePerte(TypeMouvementEmballage type) {
        return switch (type) {
            case CADEAU -> COMPTE_BOISSONS_OFFERTES;
            case PERIME -> COMPTE_BOISSONS_PERIMEES;
            default -> null;
        };
    }

    /**
     * Le compte de {@link #compteChargePerte}, lu au plan comptable ; {@code null} pour une casse. Absent (supprimé du plan
     * comptable), la perte est refusée plutôt que passée sur le compte de la boisson : elle se confondrait de nouveau avec
     * le coût des boissons vendues.
     */
    private CompteOHADA comptePerte(TypeMouvementEmballage type) {
        String numero = compteChargePerte(type);
        if (numero == null) {
            return null;
        }
        boolean offerte = type == TypeMouvementEmballage.CADEAU;
        return compteImposeOuRefus(numero, offerte ? "Boissons offertes à la clientèle" : "Boissons périmées",
            offerte ? "6276" : "6031", offerte ? "une boisson offerte" : "une boisson périmée");
    }

    /** Compte débité par la sortie de stock d'un plat : son numéro, son libellé, la racine sous laquelle le recréer, et l'opération qu'il porte. */
    record CompteSortiePlat(String numero, String libelle, String racine, String operation) {}

    /**
     * Le compte de chaque motif de sortie de plat, créés par V121. Un nouveau motif sans compte ne compile pas : le
     * {@code switch} est exhaustif.
     */
    static CompteSortiePlat compteSortiePlat(MotifSortiePlat motif) {
        return switch (motif) {
            case PERIME -> new CompteSortiePlat(COMPTE_PLATS_PERIMES, "Plats périmés", "736", "un plat périmé");
            case MOISI -> new CompteSortiePlat(COMPTE_PLATS_MOISIS, "Plats moisis ou avariés", "736", "un plat moisi ou avarié");
            case RENVERSE -> new CompteSortiePlat(COMPTE_PLATS_RENVERSES, "Plats renversés ou tombés", "736", "un plat renversé");
            case BRULE -> new CompteSortiePlat(COMPTE_PLATS_BRULES, "Plats brûlés ou ratés", "736", "un plat brûlé");
            case CADEAU -> new CompteSortiePlat(COMPTE_PLATS_OFFERTS, "Plats offerts à la clientèle", "6276", "un plat offert");
            case REPAS_PERSONNEL -> new CompteSortiePlat(COMPTE_REPAS_PERSONNEL, "Repas du personnel", "6617", "un repas du personnel");
            case AUTRE -> new CompteSortiePlat(COMPTE_AUTRES_SORTIES_PLATS, "Autres sorties de plats", "736", "une autre sortie de plat");
        };
    }

    /**
     * Le compte du motif, lu au plan comptable. Absent (supprimé du plan comptable), la sortie est refusée plutôt que passée
     * sur le compte de variation du plat : la perte se confondrait de nouveau avec le coût des plats vendus.
     */
    private CompteOHADA compteDuMotif(MotifSortiePlat motif) {
        CompteSortiePlat c = compteSortiePlat(motif);
        return compteImposeOuRefus(c.numero(), c.libelle(), c.racine(), c.operation());
    }

    /** Un compte que l'application impose à une perte ; absent du plan comptable, l'opération est refusée avec la marche à suivre. */
    private CompteOHADA compteImposeOuRefus(String numero, String libelle, String racine, String operation) {
        return compteRepository.findByNumero(numero).orElseThrow(() -> new IllegalStateException(
            "Le compte " + numero + " « " + libelle + " » n'existe pas dans le plan comptable : un administrateur doit le "
            + "recréer (Plan comptable, sous " + racine + ") avant d'enregistrer " + operation + "."));
    }

    /**
     * Annule un mouvement de vides saisi à la main. Le journal des vides
     * reste en ajout seul : l'effet sur le compteur est compensé par un
     * mouvement inverse qui cite le mouvement annulé, lequel est marqué
     * annulé et sort des pertes du tableau de bord. Pour une perte de
     * boisson (casse d'une pleine, péremption, cadeau), la sortie de stock
     * est aussi annulée — stock rétabli, pièce extournée.
     *
     * <p>Les mouvements automatiques (vente, retour de vente, échange de
     * consigne) ne s'annulent qu'avec l'opération qui les a produits ; un
     * ajustement d'inventaire, réservé à l'administrateur à la saisie, l'est
     * aussi à l'annulation.</p>
     */
    @PreAuthorize(ECRITURE_BAR)
    @Transactional
    public MouvementEmballageResponse annulerMouvementEmballage(Long id) {
        MouvementEmballage m = mouvementRepository.findByIdPourMiseAJour(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("MouvementEmballage", id));
        if (m.isAnnule()) {
            throw new TransitionInvalideException("Ce mouvement est déjà annulé.");
        }
        if (m.getAnnulationDe() != null) {
            throw new IllegalArgumentException(
                "Ce mouvement est lui-même l'annulation du mouvement n° " + m.getAnnulationDe().getId()
                + " : il ne s'annule pas.");
        }
        if (!m.getType().estSaisieManuelle()) {
            throw new IllegalArgumentException(
                "Les mouvements automatiques (vente, retour de vente, échange de consigne) "
                + "s'annulent avec l'opération qui les a produits.");
        }
        if (m.getType().estAjustement() && !estAdmin()) {
            throw new AccessDeniedException("Seul un administrateur peut annuler un ajustement d'inventaire");
        }
        if (m.getType().sortDuStockDeBoisson() && m.getMouvementStock() == null) {
            throw new IllegalStateException(
                "Cette perte a été saisie avant que les pertes ne soient reliées à leur sortie de stock : "
                + "son stock ne peut pas être rétabli d'ici. L'administrateur peut annuler la sortie depuis "
                + "la Logistique, puis corriger le compteur de vides par un ajustement.");
        }
        User auteur = currentUser.requireUser();
        EmballageBoisson e = chargerEmballage(m.getEmballage().getId());

        if (m.getMouvementStock() != null) {
            stockService.annulerMouvementInterne(m.getMouvementStock(), auteur);
        }
        int delta = m.getType().delta(m.getQuantiteBouteilles());
        if (delta != 0) {
            TypeMouvementEmballage inverse = delta < 0
                ? TypeMouvementEmballage.AJUSTEMENT_PLUS : TypeMouvementEmballage.AJUSTEMENT_MOINS;
            // Datée comme le mouvement annulé : sur toute période, les deux
            // s'annulent l'un l'autre.
            MouvementEmballage inverseM = appliquerEtJournaliser(e, inverse, Math.abs(delta), m.getDateMouvement(),
                "Annulation du mouvement n° " + m.getId() + " (" + libelleMouvementVides(m.getType()) + ")",
                null, auteur);
            inverseM.setAnnulationDe(m);
        }
        m.setAnnule(true);
        log.info("Mouvement de vides annulé [id={}, type={}, par={}]", m.getId(), m.getType(), auteur.getEmail());
        return MouvementEmballageResponse.from(m);
    }

    private static String libelleMouvementVides(TypeMouvementEmballage type) {
        return switch (type) {
            case CASSE -> "casse d'une bouteille vide";
            case CASSE_PLEINE -> "casse d'une bouteille pleine";
            case PERIME -> "boisson périmée";
            case CADEAU -> "boisson offerte";
            case AJUSTEMENT_PLUS -> "ajustement d'inventaire en plus";
            case AJUSTEMENT_MOINS -> "ajustement d'inventaire en moins";
            case VENTE -> "vente";
            case RETOUR_VENTE -> "retour de vente";
            case ACHAT -> "échange de consigne";
            case RETOUR_ACHAT -> "retour d'achat (vides rendus par le fournisseur)";
        };
    }

    private boolean estAdmin() {
        return currentUser.requireUser().getRoles().stream()
            .anyMatch(r -> r.getNom() == RoleType.ADMIN);
    }

    // ---------------------------------------------------------------------
    // Tableau de bord
    // ---------------------------------------------------------------------

    /** Libellés des types de perte affichés au tableau de bord. */
    private static final Map<String, String> LABELS_PERTE = Map.of(
        "CASSE_PLEINE", "Casse (bouteille pleine)",
        "PERIME", "Boisson périmée",
        "CADEAU", "Boisson offerte (cadeau)",
        "CASSE", "Casse (bouteille vide)"
    );

    /**
     * Tableau de bord du module Restaurant sur une période : parc
     * d'emballages, ventes, achats, profit et classement des boissons.
     *
     * <p><b>Valorisation.</b> Le coût des ventes est lu sur les sorties de
     * stock de la période, donc au coût HISTORIQUE réellement passé en charge
     * au grand livre — et non recalculé au CMUP courant, ce qui produisait une
     * marge ne correspondant à aucune écriture dès que le CMUP variait. La
     * valeur du stock restant et celle des pertes d'emballage restent au CMUP
     * courant : ce sont des positions à date, pas des flux.</p>
     *
     * <p>Toutes les valeurs monétaires de ce tableau de bord (chiffre
     * d'affaires, marge, profit, valeur de stock, achats, pertes) sont en
     * USD — la devise de base du grand livre, celle du CMUP et de la valeur
     * de stock. {@code Article.prixVente} (et donc {@code LigneVente.prixUnitaire}
     * calculé côté client) est saisi en FC par convention du menu, indépendamment
     * de la devise de base — voir {@code pages/ventes/nouvelle.vue}. Le montant
     * de chaque ligne de vente est donc ramené en USD via {@link #montantEnUSD}
     * avant d'être combiné au CMUP ; les mélanger sans conversion produirait
     * une marge dénuée de sens (FC moins USD).</p>
     */
    @PreAuthorize(LECTURE_BAR)
    @Transactional(readOnly = true)
    public TableauBordRestaurantResponse tableauBord(LocalDate du, LocalDate au) {
        List<EmballageBoisson> emballages = emballageRepository.findAllAvecBoisson();
        Map<Long, EmballageBoisson> emballageParBoisson = new LinkedHashMap<>();
        int totalVides = 0, totalCasiers = 0, totalRestantes = 0;
        for (EmballageBoisson e : emballages) {
            emballageParBoisson.put(e.getArticleBoisson().getId(), e);
            totalVides += e.getBouteillesVides();
            totalCasiers += e.casiers();
            totalRestantes += e.bouteillesRestantes();
        }

        // CMUP courant par boisson, agrégé tous entrepôts confondus (ce
        // module suppose un usage mono-entrepôt ; agréger reste correct même
        // si plusieurs sont utilisés, seul le détail par entrepôt se perdrait).
        // Boissons seulement : ce tableau de bord est celui du bar (bouteilles, casiers, boissons les plus vendues). Y
        // compter les plats gonflait la valeur du stock et le nombre d'articles sous le seuil, et laissait le
        // responsable bar voir la valeur du stock de la cuisine.
        Set<Long> idsCarte = articleRepository.findAll().stream()
            .filter(a -> a.getType() == TypeArticle.BOISSON)
            .map(Article::getId)
            .collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal[]> qteEtValeurParArticle = new LinkedHashMap<>(); // [quantite, valeur]
        boolean sousSeuilGlobal = false;
        int nbSousSeuil = 0;
        for (StockNiveauResponse s : stockService.etatStockInterne()) {
            if (!idsCarte.contains(s.articleId())) continue;
            qteEtValeurParArticle.merge(s.articleId(),
                new BigDecimal[]{s.quantite(), s.valeurTotale()},
                (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
            if (s.sousSeuil()) nbSousSeuil++;
        }
        Map<Long, BigDecimal> cmupParArticle = new LinkedHashMap<>();
        BigDecimal valeurStockPleines = BigDecimal.ZERO;
        for (var entry : qteEtValeurParArticle.entrySet()) {
            BigDecimal qte = entry.getValue()[0];
            BigDecimal valeur = entry.getValue()[1];
            valeurStockPleines = valeurStockPleines.add(valeur);
            cmupParArticle.put(entry.getKey(),
                // 6 décimales : voir StockNiveauResponse.
                qte.signum() == 0 ? BigDecimal.ZERO : valeur.divide(qte, 6, RoundingMode.HALF_UP));
        }

        // ── Ventes ──────────────────────────────────────────────────────
        Map<Long, BigDecimal[]> venteParArticle = new LinkedHashMap<>(); // [quantite, montantUSD]
        Map<LocalDate, BigDecimal[]> venteParJour = new TreeMap<>();
        Set<Long> ventesDistinctes = new java.util.HashSet<>();
        for (LigneVente l : venteRepository.ligneVentesBoissonsPeriode(du, au)) {
            Vente v = l.getVente();
            ventesDistinctes.add(v.getId());
            BigDecimal montantUSD = montantEnUSD(l.getPrixUnitaire().multiply(l.getQuantite()), v);
            venteParArticle.merge(l.getArticle().getId(), new BigDecimal[]{l.getQuantite(), montantUSD},
                (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
            venteParJour.merge(v.getDateVente(), new BigDecimal[]{l.getQuantite(), montantUSD},
                (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }
        BigDecimal quantiteVendue = venteParArticle.values().stream()
            .map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chiffreAffaires = venteParArticle.values().stream()
            .map(a -> a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Achats (réceptions) ─────────────────────────────────────────
        BigDecimal quantiteAchetee = BigDecimal.ZERO, montantAchats = BigDecimal.ZERO;
        Set<Long> receptionsDistinctes = new java.util.HashSet<>();
        for (MouvementStock m : mouvementStockRepository.receptionsBoissonsPeriode(du, au)) {
            receptionsDistinctes.add(m.getId());
            for (LigneMouvementStock l : m.getLignes()) {
                if (l.getArticle().getType() != TypeArticle.BOISSON) continue;
                quantiteAchetee = quantiteAchetee.add(l.getQuantite());
                montantAchats = montantAchats.add(l.getMontant());
            }
        }

        // ── Pertes (casse pleine, périmé, cadeau, casse de vide) ─────────
        Map<String, int[]> qtePerteParType = new LinkedHashMap<>(); // [quantite]
        Map<String, BigDecimal> valeurPerteParType = new LinkedHashMap<>();
        BigDecimal valeurPertes = BigDecimal.ZERO;
        for (MouvementEmballage m : mouvementRepository.rechercherPeriode(du, au)) {
            // Une perte annulée n'a pas eu lieu : sa sortie de stock a été
            // rétablie, elle ne doit pas non plus peser ici.
            if (m.isAnnule()) {
                continue;
            }
            TypeMouvementEmballage type = m.getType();
            if (type != TypeMouvementEmballage.CASSE_PLEINE && type != TypeMouvementEmballage.PERIME
                && type != TypeMouvementEmballage.CADEAU && type != TypeMouvementEmballage.CASSE) {
                continue;
            }
            String cle = type.name();
            qtePerteParType.computeIfAbsent(cle, k -> new int[1])[0] += m.getQuantiteBouteilles();
            // La casse d'un simple vide n'a pas de valeur marchande (pas de
            // boisson dedans) : seules les trois autres pertes valorisent.
            if (type != TypeMouvementEmballage.CASSE) {
                Long boissonId = m.getEmballage().getArticleBoisson().getId();
                BigDecimal cmup = cmupParArticle.getOrDefault(boissonId, BigDecimal.ZERO);
                BigDecimal valeur = cmup.multiply(BigDecimal.valueOf(m.getQuantiteBouteilles()));
                valeurPerteParType.merge(cle, valeur, BigDecimal::add);
                valeurPertes = valeurPertes.add(valeur);
            }
        }

        // ── Cout des ventes : cout HISTORIQUE, lu sur les sorties de stock ──
        // Chaque sortie a ete valorisee au CMP en vigueur A CE MOMENT-LA par
        // StockService, et ce montant est celui reellement passe en charge au
        // grand livre. Le recalculer au CMP courant (ce que faisait ce tableau
        // de bord) donnait une marge qui ne correspondait a aucune ecriture des
        // que le CMP bougeait dans la periode. On lit donc le cout deja pose.
        //
        // Les sorties couvrent aussi casse/peremption/cadeau : elles sont
        // isolees ici pour rester distinguees des ventes, la ou la valeur des
        // pertes garde sa propre ligne au tableau de bord.
        BigDecimal coutSortiesTotal = BigDecimal.ZERO;
        BigDecimal quantiteSortie = BigDecimal.ZERO;
        for (MouvementStock m : mouvementStockRepository.sortiesBoissonsPeriode(du, au)) {
            for (LigneMouvementStock l : m.getLignes()) {
                if (l.getArticle().getType() != TypeArticle.BOISSON) continue;
                coutSortiesTotal = coutSortiesTotal.add(l.getMontant());
                quantiteSortie = quantiteSortie.add(l.getQuantite());
            }
        }
        // Cout unitaire moyen effectivement constate sur la periode : sert a
        // ventiler le cout total entre ventes et pertes au prorata des
        // quantites, une sortie ne portant pas son motif.
        BigDecimal coutUnitaireMoyen = quantiteSortie.signum() == 0
            ? BigDecimal.ZERO
            : coutSortiesTotal.divide(quantiteSortie, 6, RoundingMode.HALF_UP);
        BigDecimal coutDesVentes = coutUnitaireMoyen.multiply(quantiteVendue).setScale(2, RoundingMode.HALF_UP);

        BigDecimal margeBrute = chiffreAffaires.subtract(coutDesVentes);
        BigDecimal profitNet = margeBrute.subtract(valeurPertes);

        // ── Détail et classement par boisson ─────────────────────────────
        List<BoissonStatResponse> parBoisson = new ArrayList<>();
        for (Long articleId : idsCarte) {
            Article a = articleRepository.findById(articleId).orElse(null);
            if (a == null || a.getType() != TypeArticle.BOISSON) continue;
            BigDecimal[] vente = venteParArticle.getOrDefault(articleId, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] stock = qteEtValeurParArticle.get(articleId);
            EmballageBoisson emb = emballageParBoisson.get(articleId);
            parBoisson.add(new BoissonStatResponse(
                articleId, a.getCode(), a.getLibelle(),
                vente[0], vente[1],
                stock == null ? BigDecimal.ZERO : stock[0],
                emb == null ? null : emb.getBouteillesVides(),
                emb == null ? null : emb.casiers(),
                emb == null ? null : emb.getContenanceCasier()
            ));
        }
        List<BoissonStatResponse> topBoissons = parBoisson.stream()
            .filter(b -> b.quantiteVendue().signum() > 0)
            .sorted(Comparator.comparing(BoissonStatResponse::quantiteVendue).reversed())
            .limit(5)
            .toList();

        List<VentesJourResponse> ventesJour = venteParJour.entrySet().stream()
            .map(e -> new VentesJourResponse(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList();

        List<PerteTypeResponse> pertesParType = qtePerteParType.entrySet().stream()
            .map(e -> new PerteTypeResponse(e.getKey(), LABELS_PERTE.getOrDefault(e.getKey(), e.getKey()),
                e.getValue()[0], valeurPerteParType.getOrDefault(e.getKey(), BigDecimal.ZERO)))
            .toList();

        return new TableauBordRestaurantResponse(
            du, au,
            totalVides, totalCasiers, totalRestantes, emballages.size(),
            valeurStockPleines, nbSousSeuil,
            quantiteVendue, chiffreAffaires, ventesDistinctes.size(),
            quantiteAchetee, montantAchats, receptionsDistinctes.size(),
            margeBrute, valeurPertes, profitNet,
            topBoissons, parBoisson, ventesJour, pertesParType
        );
    }

    /**
     * Ramène un montant tenu dans la devise d'une vente vers l'USD — devise
     * de base du grand livre, celle dans laquelle le CMUP et la valeur de
     * stock ({@code StockService.etatStockInterne}) sont déjà exprimés.
     *
     * <p>{@code Article.prixVente} est saisi en FC (convention propre au
     * menu, indépendante de la devise de base — voir
     * {@code pages/logistique/articles/index.vue}), donc une vente en CDF
     * doit être divisée par son taux du jour pour rejoindre l'USD ; une vente
     * déjà en USD n'a rien à convertir. Sans cette conversion, soustraire le
     * CMUP (USD) d'un chiffre d'affaires resté en FC produirait une marge
     * dénuée de sens — l'erreur commise à l'origine de ce tableau de bord.</p>
     */
    private BigDecimal montantEnUSD(BigDecimal montant, Vente vente) {
        if (vente.getDevise() == Devise.USD) {
            return montant;
        }
        // Une vente sans taux enregistré est convertie au taux en vigueur à
        // sa date — jamais laissée en francs : additionnés tels quels aux
        // dollars, 56 000 FC comptaient pour 56 000 USD de chiffre
        // d'affaires. Sans aucun taux connu, la conversion échoue avec un
        // message explicite plutôt que de produire un total faux.
        BigDecimal taux = vente.getTauxJournalier();
        if (taux == null || taux.signum() <= 0) {
            taux = conversionDevise.tauxALaDate(vente.getDateVente());
        }
        return conversionDevise.enDeviseBase(montant, vente.getDevise(), taux).montantBase();
    }

    /**
     * Analyse des ventes de la carte sur une période arbitraire (semaine,
     * mois, trimestre... le découpage est décidé côté client, qui fournit
     * simplement les bornes) : meilleures/moins bonnes ventes, marge par
     * article, par catégorie et tendance journalière. Ouverte à ceux qui
     * pilotent l'offre : voir {@link #LECTURE_ANALYSES}.
     *
     * <p>Le coût de chaque ligne est le coût HISTORIQUE réellement passé en
     * charge (lu sur la sortie de stock liée à la vente), jamais recalculé
     * au CMP courant — même principe que {@link #tableauBord}, appliqué ici
     * ligne à ligne plutôt qu'en moyenne globale : une marge par article n'a
     * de sens que si chaque article porte son propre coût, pas une moyenne
     * de tous les autres.</p>
     */
    @PreAuthorize(LECTURE_ANALYSES)
    @Transactional(readOnly = true)
    public AnalyseVentesResponse analyserVentes(LocalDate du, LocalDate au) {
        // Domaine de l'appelant : un responsable cuisine n'analyse que les plats, un responsable bar que les
        // boissons. Le filtre porte sur les lignes (donc sur les totaux, les categories et la tendance, qui en
        // decoulent) et sur la liste des articles jamais vendus ; le cout reste celui de chaque article.
        PerimetreRestaurant.Perimetre perimetre = PerimetreRestaurant.restaurantCourant();
        List<LigneVente> lignes = venteRepository.ligneVentesCartePeriode(du, au).stream()
            .filter(l -> perimetre.autorise(l.getArticle().getType()))
            .toList();

        // Cout reel de chaque (mouvement, article) : un seul aller-retour en
        // base pour toutes les ventes de la periode plutot qu'un par ligne.
        //
        // Une vente peut porter PLUSIEURS lignes du meme article (ex. "une
        // boisson de plus" sur une table deja servie, voir
        // VenteService.ajouterLigne) : chacune devient sa propre ligne de
        // sortie de stock, au meme cout unitaire mais avec son propre
        // montant. Les additionner (merge) est indispensable — un simple
        // ecrasement (put) perdait le cout de toutes les lignes sauf la
        // derniere lue, sous-evaluant le cout total impute a cette vente.
        Set<Long> mouvementIds = new java.util.HashSet<>();
        for (LigneVente l : lignes) {
            MouvementStock m = l.getVente().getMouvement();
            if (m != null) mouvementIds.add(m.getId());
        }
        Map<String, BigDecimal> coutParMouvementArticle = new LinkedHashMap<>();
        if (!mouvementIds.isEmpty()) {
            for (MouvementStock m : mouvementStockRepository.findAllByIdInWithLignes(mouvementIds)) {
                for (LigneMouvementStock lms : m.getLignes()) {
                    coutParMouvementArticle.merge(
                        m.getId() + ":" + lms.getArticle().getId(), lms.getMontant(), BigDecimal::add);
                }
            }
        }
        // Quantite totale vendue par (mouvement, article) : sert a repartir
        // le cout agrege ci-dessus au prorata de CHAQUE ligne de vente,
        // plutot que de le lui attribuer en entier — ce qui le compterait
        // une fois par ligne partageant le meme article, au lieu d'une seule
        // fois au total.
        Map<String, BigDecimal> quantiteParMouvementArticle = new LinkedHashMap<>();
        for (LigneVente l : lignes) {
            MouvementStock m = l.getVente().getMouvement();
            if (m == null) continue;
            quantiteParMouvementArticle.merge(
                m.getId() + ":" + l.getArticle().getId(), l.getQuantite(), BigDecimal::add);
        }

        record Cumul(BigDecimal quantite, BigDecimal ca, BigDecimal cout) {
            Cumul plus(BigDecimal q, BigDecimal c, BigDecimal k) {
                return new Cumul(quantite.add(q), ca.add(c), cout.add(k));
            }
        }
        Map<Long, Cumul> parArticleId = new LinkedHashMap<>();
        Map<String, Cumul> parCategorieCle = new LinkedHashMap<>();
        Map<String, String[]> categorieInfo = new LinkedHashMap<>(); // cle -> [categorie, type]
        Map<LocalDate, Cumul> parJourMap = new TreeMap<>();

        for (LigneVente l : lignes) {
            Vente v = l.getVente();
            Article a = l.getArticle();
            BigDecimal ca = montantEnUSD(l.getPrixUnitaire().multiply(l.getQuantite()), v);
            MouvementStock m = v.getMouvement();
            BigDecimal cout = BigDecimal.ZERO;
            if (m != null) {
                String cle = m.getId() + ":" + a.getId();
                BigDecimal coutTotalCle = coutParMouvementArticle.getOrDefault(cle, BigDecimal.ZERO);
                BigDecimal quantiteTotaleCle = quantiteParMouvementArticle.getOrDefault(cle, BigDecimal.ZERO);
                // Repartition au prorata de la quantite de CETTE ligne dans le
                // total vendu de cet article sur cette vente : attribuer le
                // cout total a chaque ligne partageant la cle le compterait
                // plusieurs fois des qu'un article a plus d'une ligne.
                cout = quantiteTotaleCle.signum() == 0 ? BigDecimal.ZERO
                    : coutTotalCle.multiply(l.getQuantite()).divide(quantiteTotaleCle, 2, RoundingMode.HALF_UP);
            }

            parArticleId.merge(a.getId(), new Cumul(l.getQuantite(), ca, cout),
                (x, y) -> x.plus(y.quantite(), y.ca(), y.cout()));

            String categorie = a.getCategorie() != null && !a.getCategorie().isBlank() ? a.getCategorie() : "Sans catégorie";
            String cleCategorie = a.getType() + "|" + categorie;
            categorieInfo.putIfAbsent(cleCategorie, new String[]{categorie, a.getType().name()});
            parCategorieCle.merge(cleCategorie, new Cumul(l.getQuantite(), ca, cout),
                (x, y) -> x.plus(y.quantite(), y.ca(), y.cout()));

            parJourMap.merge(v.getDateVente(), new Cumul(l.getQuantite(), ca, BigDecimal.ZERO),
                (x, y) -> x.plus(y.quantite(), y.ca(), BigDecimal.ZERO));
        }

        // Un article actif de la carte jamais vendu sur la periode est le
        // signal le plus fort de "moins vendu" qui soit — l'omettre (comme
        // ferait une simple agregation des lignes de vente) le cacherait
        // derriere les articles vendus au moins une fois.
        for (Article a : articleRepository.findAll()) {
            if (!TYPES_CARTE.contains(a.getType()) || !a.isActif() || !perimetre.autorise(a.getType())) continue;
            parArticleId.putIfAbsent(a.getId(), new Cumul(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        List<AnalyseVentesResponse.ArticleStatResponse> parArticle = new ArrayList<>();
        for (var entry : parArticleId.entrySet()) {
            Article a = articleRepository.findById(entry.getKey()).orElse(null);
            if (a == null) continue;
            Cumul c = entry.getValue();
            BigDecimal marge = c.ca().subtract(c.cout());
            BigDecimal margePct = c.ca().signum() == 0 ? BigDecimal.ZERO
                : marge.divide(c.ca(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            parArticle.add(new AnalyseVentesResponse.ArticleStatResponse(
                a.getId(), a.getCode(), a.getLibelle(), a.getType().name(),
                a.getCategorie() != null && !a.getCategorie().isBlank() ? a.getCategorie() : "Sans catégorie",
                c.quantite(), c.ca(), c.cout(), marge, margePct
            ));
        }
        parArticle.sort(Comparator.comparing(AnalyseVentesResponse.ArticleStatResponse::quantiteVendue).reversed());

        List<AnalyseVentesResponse.CategorieStatResponse> parCategorie = parCategorieCle.entrySet().stream()
            .map(e -> {
                String[] info = categorieInfo.get(e.getKey());
                Cumul c = e.getValue();
                return new AnalyseVentesResponse.CategorieStatResponse(
                    info[0], info[1], c.quantite(), c.ca(), c.cout(), c.ca().subtract(c.cout()));
            })
            .sorted(Comparator.comparing(AnalyseVentesResponse.CategorieStatResponse::chiffreAffaires).reversed())
            .toList();

        List<AnalyseVentesResponse.VenteJourResponse> parJour = parJourMap.entrySet().stream()
            .map(e -> new AnalyseVentesResponse.VenteJourResponse(e.getKey(), e.getValue().quantite(), e.getValue().ca()))
            .toList();

        BigDecimal totalQuantite = parArticle.stream().map(AnalyseVentesResponse.ArticleStatResponse::quantiteVendue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCa = parArticle.stream().map(AnalyseVentesResponse.ArticleStatResponse::chiffreAffaires)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCout = parArticle.stream().map(AnalyseVentesResponse.ArticleStatResponse::cout)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AnalyseVentesResponse(
            du, au, totalQuantite, totalCa, totalCout, totalCa.subtract(totalCout),
            parArticle, parCategorie, parJour
        );
    }

    // ---------------------------------------------------------------------
    // Variantes internes appelées par le module Vente / le paiement des
    // notes de frais (CaisseService)
    //
    // Sans @PreAuthorize : l'autorisation est portée par le service appelant
    // (VenteService pour les ventes, CaisseService — rôle CAISSIER — pour le
    // paiement d'une note), même principe que
    // StockService.enregistrerSortieVenteInterne. Ne jamais exposer via un
    // contrôleur.
    // ---------------------------------------------------------------------

    /**
     * Échange de consigne consécutif à un achat de boissons réglé par note de
     * frais : appelée par {@code CaisseService.payerNote} au moment du
     * paiement, pour chaque ligne d'achat de boisson dont l'échange a été
     * demandé à la création de la note (voir
     * {@code NoteFraisService.validerNoteRespRestaurant}, qui garantit déjà
     * l'existence du conditionnement à la création).
     *
     * <p>Le paiement — déjà vérifié par le DFIN puis validé par le DA — ne
     * doit JAMAIS échouer pour une raison d'emballages. Deux garde-fous :</p>
     * <ul>
     *   <li>absence de conditionnement : ignorée silencieusement ;</li>
     *   <li>vides insuffisants : la reprise est plafonnée au stock réellement
     *       disponible, comme à l'annulation d'une vente
     *       ({@link #annulerVidesSurVenteInterne}). Plusieurs jours peuvent
     *       s'écouler entre la demande d'achat et son règlement, et les ventes
     *       ou la casse consomment les vides entre-temps ; laisser remonter
     *       l'exception bloquait le décaissement d'un fournisseur déjà livré,
     *       sans aucune porte de sortie (une note transmise n'est ni
     *       annulable ni modifiable). L'écart est tracé dans le motif : la
     *       dette de consigne se règle avec le fournisseur, elle n'a pas à
     *       retenir un paiement approuvé.</li>
     * </ul>
     */
    @Transactional
    public void enregistrerAchatVidesDepuisNoteFraisInterne(Article articleBoisson, int quantiteBouteilles,
                                                             LocalDate date, String motif, User auteur) {
        emballageRepository.findByArticleBoissonIdPourMiseAJour(articleBoisson.getId())
            .ifPresent(e -> {
                int rendus = plafonner(quantiteBouteilles, e.getBouteillesVides());
                if (rendus <= 0) {
                    log.warn("Échange de consigne sans effet [boisson={}, demandé={}, disponible=0]",
                        articleBoisson.getCode(), quantiteBouteilles);
                    return;
                }
                String trace = rendus < quantiteBouteilles
                    ? motif + " — plafonné à " + rendus + " sur " + quantiteBouteilles
                        + " (vides insuffisants au règlement)"
                    : motif;
                appliquerEtJournaliser(e, TypeMouvementEmballage.ACHAT, rendus, date, trace, null, auteur);
            });
    }

    /**
     * Retour de fonds sur un achat de boissons avec échange de consigne (note réduite après paiement, voir
     * {@code RetourFondsService}) : le fournisseur rend les vides des bouteilles non livrées ou reprises —
     * le symétrique de {@link #enregistrerAchatVidesDepuisNoteFraisInterne}, +1 vide par bouteille. Sans
     * conditionnement pour la boisson, rien à faire (aucun vide n'avait été rendu à l'achat).
     */
    @Transactional
    public void enregistrerRetourVidesAchatInterne(Article articleBoisson, int quantiteBouteilles,
                                                   LocalDate date, String motif, User auteur) {
        if (quantiteBouteilles <= 0) {
            return;
        }
        emballageRepository.findByArticleBoissonIdPourMiseAJour(articleBoisson.getId())
            .ifPresent(e -> appliquerEtJournaliser(e, TypeMouvementEmballage.RETOUR_ACHAT, quantiteBouteilles,
                date, motif, null, auteur));
    }

    /**
     * Chaque bouteille vendue revient en stock de vides. Appelé à la
     * validation d'une vente, pour ses seules lignes de boisson disposant d'un
     * conditionnement.
     */
    @Transactional
    public void enregistrerVidesSurVenteInterne(Vente vente, User auteur) {
        lignesBoissonOrdonnees(vente)
            .forEach(l -> emballageRepository.findByArticleBoissonIdPourMiseAJour(l.getArticle().getId())
                .ifPresent(e -> {
                    int bouteilles = bouteilles(l.getQuantite());
                    if (bouteilles <= 0) {
                        return;
                    }
                    appliquerEtJournaliser(e, TypeMouvementEmballage.VENTE, bouteilles,
                        vente.getDateVente(), "Vente " + vente.getReference(), vente, auteur);
                }));
    }

    /**
     * Annulation d'une vente : on reprend les vides qu'elle avait fait entrer.
     *
     * <p>La reprise est plafonnée au stock disponible. Refuser d'annuler une
     * vente parce qu'un achat a consommé les bouteilles entre-temps serait
     * absurde ; on applique donc ce qui peut l'être et le journal reste égal
     * au compteur.</p>
     */
    @Transactional
    public void annulerVidesSurVenteInterne(Vente vente, User auteur) {
        lignesBoissonOrdonnees(vente)
            .forEach(l -> emballageRepository.findByArticleBoissonIdPourMiseAJour(l.getArticle().getId())
                .ifPresent(e -> {
                    int reprise = plafonner(bouteilles(l.getQuantite()), e.getBouteillesVides());
                    if (reprise <= 0) {
                        return;
                    }
                    appliquerEtJournaliser(e, TypeMouvementEmballage.RETOUR_VENTE, reprise,
                        vente.getDateVente(), "Annulation vente " + vente.getReference(), vente, auteur);
                }));
    }

    /**
     * Retour remboursé d'une boisson consignée remise en stock pleine : la bouteille n'a pas été vidée, on reprend le vide
     * que la vente avait fait entrer (RETOUR_VENTE). Plafonné au stock de vides, comme l'annulation d'une vente. Sans effet
     * pour une boisson sans conditionnement (V134).
     */
    @Transactional
    public void reprendreVidesSurRetourInterne(Article boisson, BigDecimal quantite, LocalDate date, String motif, Vente vente,
                                               User auteur) {
        if (boisson == null || boisson.getType() != TypeArticle.BOISSON) {
            return;
        }
        emballageRepository.findByArticleBoissonIdPourMiseAJour(boisson.getId()).ifPresent(e -> {
            int reprise = plafonner(bouteilles(quantite), e.getBouteillesVides());
            if (reprise > 0) {
                appliquerEtJournaliser(e, TypeMouvementEmballage.RETOUR_VENTE, reprise, date, motif, vente, auteur);
            }
        });
    }

    /**
     * Lignes de boisson d'une vente, triées par identifiant d'article.
     *
     * <p>L'ordre importe : chaque ligne pose un verrou sur son conditionnement
     * ({@code findByArticleBoissonIdPourMiseAJour}) jusqu'à la fin de la
     * transaction. Deux ventes simultanées portant sur les mêmes boissons dans
     * un ordre différent se verrouilleraient mutuellement ; un ordre commun à
     * toutes les transactions supprime ce risque d'interblocage.</p>
     */
    private java.util.stream.Stream<LigneVente> lignesBoissonOrdonnees(Vente vente) {
        // Le type du parametre est explicite a dessein : laisse implicite, javac
        // doit inferer simultanement les deux parametres de type de
        // Comparator.comparing a travers le filter qui precede, ce qui faisait
        // exploser le temps de compilation du module (plus de 45 minutes).
        return vente.getLignes().stream()
            .filter(l -> l.getArticle().getType() == TypeArticle.BOISSON)
            .sorted(Comparator.comparing((LigneVente l) -> l.getArticle().getId()));
    }

    // ---------------------------------------------------------------------
    // Règle métier
    // ---------------------------------------------------------------------

    /**
     * Applique un mouvement au stock de vides et l'inscrit au journal, dans la
     * même transaction — c'est ce qui garantit que la somme signée du journal
     * égale toujours le compteur.
     */
    private MouvementEmballage appliquerEtJournaliser(EmballageBoisson e, TypeMouvementEmballage type,
                                                      int quantite, LocalDate date, String motif,
                                                      Vente vente, User auteur) {
        e.setBouteillesVides(appliquer(e.getBouteillesVides(), type, quantite));
        emballageRepository.save(e);

        MouvementEmballage m = MouvementEmballage.builder()
            .emballage(e)
            .type(type)
            .quantiteBouteilles(quantite)
            .dateMouvement(date == null ? LocalDate.now() : date)
            .motif(motif)
            .vente(vente)
            .createdBy(auteur)
            .build();
        return mouvementRepository.save(m);
    }

    /**
     * Nouveau stock de bouteilles vides apres un mouvement.
     *
     * <p>Fonction pure, sans effet de bord ni dependance Spring : c'est la
     * seule regle metier du suivi des emballages, isolee pour rester
     * verifiable telle quelle.</p>
     *
     * @throws IllegalArgumentException si la quantite n'est pas strictement
     *         positive, ou si le mouvement rendrait le stock negatif
     */
    public static int appliquer(int videsAvant, TypeMouvementEmballage type, int quantite) {
        if (quantite <= 0) {
            throw new IllegalArgumentException("La quantité doit être strictement positive");
        }
        int apres = videsAvant + type.delta(quantite);
        if (apres < 0) {
            throw new IllegalArgumentException(
                "Bouteilles vides insuffisantes : " + videsAvant + " en stock, " + quantite + " demandée(s)");
        }
        return apres;
    }

    /**
     * Quantité de bouteilles concernées par une ligne de vente.
     *
     * <p>Une bouteille ne se divise pas, alors que {@code LigneVente.quantite}
     * est un décimal : une quantité fractionnaire est arrondie au plus proche
     * plutôt que tronquée. Tronquer biaisait systématiquement à la baisse, et
     * ramenait une vente inférieure à une bouteille à zéro — ce que
     * {@link #appliquer} refusait, faisant échouer la vente entière pour une
     * question d'emballage. Physiquement, toute bouteille ouverte produit un
     * vide : arrondir au plus proche est la lecture la plus fidèle.</p>
     */
    static int bouteilles(BigDecimal quantite) {
        return quantite == null ? 0 : quantite.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Quantité réellement applicable au stock de vides : jamais plus que ce
     * qui s'y trouve, jamais négative.
     *
     * <p>Utilisée sur les chemins où refuser l'opération serait pire que
     * l'appliquer partiellement — annulation d'une vente, règlement d'une note
     * de frais déjà approuvée. Sur les saisies manuelles, au contraire,
     * {@link #appliquer} refuse : l'utilisateur est devant l'écran et doit
     * corriger sa saisie.</p>
     */
    static int plafonner(int demande, int disponible) {
        return Math.max(0, Math.min(demande, disponible));
    }

    /**
     * Charge un conditionnement en vue de modifier son compteur, avec un
     * verrou exclusif tenu jusqu'à la fin de la transaction — voir
     * {@link EmballageBoissonRepository#findByIdPourMiseAJour}.
     */
    private EmballageBoisson chargerEmballage(Long id) {
        return emballageRepository.findByIdPourMiseAJour(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Emballage", id));
    }
}

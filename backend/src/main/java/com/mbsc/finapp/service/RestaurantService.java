package com.mbsc.finapp.service;

import com.mbsc.finapp.service.DocumentationCompteService.RoleCompteArticle;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.EmballageBoisson;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneProduction;
import com.mbsc.finapp.domain.LigneRecette;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.Production;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.SalleRestaurant;
import com.mbsc.finapp.domain.SortiePlat;
import com.mbsc.finapp.domain.StockGrandLivre;
import com.mbsc.finapp.domain.TableRestaurant;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.FormeTable;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.StatutProduction;
import com.mbsc.finapp.domain.enums.StatutVente;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;
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
import com.mbsc.finapp.dto.restaurant.LotStockResponse;
import com.mbsc.finapp.dto.restaurant.LigneProductionRequest;
import com.mbsc.finapp.dto.restaurant.LigneRecetteRequest;
import com.mbsc.finapp.dto.restaurant.LigneRecetteResponse;
import com.mbsc.finapp.dto.restaurant.PlanSalleRequest;
import com.mbsc.finapp.dto.restaurant.ProductionRequest;
import com.mbsc.finapp.dto.restaurant.ProductionResponse;
import com.mbsc.finapp.dto.restaurant.ProvisionEntreeRequest;
import com.mbsc.finapp.dto.restaurant.ProvisionSortieRequest;
import com.mbsc.finapp.dto.restaurant.RecetteRequest;
import com.mbsc.finapp.dto.restaurant.RecetteResponse;
import com.mbsc.finapp.dto.restaurant.SalleRequest;
import com.mbsc.finapp.dto.restaurant.SalleResponse;
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
import com.mbsc.finapp.repository.ProductionRepository;
import com.mbsc.finapp.repository.SalleRestaurantRepository;
import com.mbsc.finapp.repository.SortiePlatRepository;
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
 * via {@link #enregistrerAchatVidesDepuisNoteFraisInterne}.</p>
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
    /** Ecriture directe de l'achat d'une provision — voir {@link #recevoirProvision}. */
    private final ComptabiliteService comptabilite;
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

    private static final String LECTURE =
        "hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final String ECRITURE = "hasAnyRole('RESP_RESTAURANT', 'ADMIN')";
    /**
     * Occuper/liberer une table est une action de service (le caissier y a
     * autant besoin qu'au responsable restaurant), a distinguer de {@link
     * #ECRITURE} qui couvre aussi la production, les provisions et les
     * emballages — restes hors du quotidien du caissier.
     */
    private static final String ECRITURE_STATUT_TABLE = "hasAnyRole('RESP_RESTAURANT', 'CAISSIER', 'ADMIN')";
    /**
     * Analyse des ventes : un outil de pilotage de l'offre, reserve parmi les
     * lecteurs du module ({@link #LECTURE}) a ceux qui la pilotent — le
     * responsable restaurant, le DFIN et le DG. Exposee sous /restaurant
     * comme le reste du module, donc soumise a son activation et a ses
     * permissions (le DFIN et le DG y ont la lecture).
     */
    private static final String LECTURE_ANALYSES = "hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'ADMIN')";
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

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<ArticleResponse> listerCarte() {
        return articleRepository.findAllWithComptes().stream()
            .filter(a -> TYPES_CARTE.contains(a.getType()))
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
            req.prixVente(), req.prixAchat(), req.minerais(), req.soumisTva(), req.stockMin(), req.actif()
        );
        ArticleResponse cree = stockService.creerArticleInterne(reqAvecComptes);
        log.info("Article de carte créé [code={}, type={}]", cree.code(), cree.type());
        return cree;
    }

    /**
     * Code proposé pour une nouvelle boisson, déduit de son libellé et de sa société (voir
     * {@link GenerateurCodeArticle}) et libre au moment de l'appel : l'écran de création
     * l'affiche au fil de la saisie. Il est à nouveau demandé juste avant l'enregistrement, et
     * l'unicité reste contrôlée à la création.
     */
    @PreAuthorize(ECRITURE_CARTE)
    @Transactional(readOnly = true)
    public CodeArticleSuggereResponse suggererCodeArticleCarte(String libelle, String societe) {
        return new CodeArticleSuggereResponse(
            GenerateurCodeArticle.generer(libelle, societe, articleRepository::existsByCode));
    }

    @PreAuthorize(ECRITURE_CARTE)
    @Transactional
    public ArticleResponse modifierArticleCarte(Long id, ArticleRequest req) {
        exigerTypeCarte(req.type());
        Article existant = exigerArticleDeLaCarte(id);
        return stockService.modifierArticleInterne(id, completerParLExistant(existant, req));
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
            req.actif() != null ? req.actif() : a.isActif()
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

    /** Etat du stock limite aux articles de la carte. */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<StockNiveauResponse> etatStockCarte() {
        Set<Long> idsCarte = articleRepository.findAll().stream()
            .filter(a -> TYPES_CARTE.contains(a.getType()))
            .map(Article::getId)
            .collect(java.util.stream.Collectors.toSet());
        return stockService.etatStockInterne().stream()
            .filter(s -> idsCarte.contains(s.articleId()))
            .toList();
    }

    /**
     * Lots de stock actifs des boissons (date d'achat, fournisseur, prix) —
     * suivi de gestion séparé du coût moyen pondéré ci-dessus, voir
     * {@link LotStockService}. Un plat n'a pas de lot d'achat : il ne figure
     * que sur {@link #etatStockCarte()}.
     */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<LotStockResponse> listerLotsBoissons() {
        return lotStockRepository.findByArticle_TypeAndQuantiteRestanteGreaterThanOrderByDateEntreeAsc(
                TypeArticle.BOISSON, java.math.BigDecimal.ZERO)
            .stream().map(LotStockResponse::from).toList();
    }

    /** Entrepots disponibles pour une reception, sans exiger le module LOGISTIQUE. */
    @PreAuthorize(LECTURE)
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

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public RecetteResponse consulterRecette(Long platId) {
        Article plat = exigerPlat(platId);
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId),
            cmpParArticle());
    }

    /** Fiches de tous les plats, y compris ceux qui n'en ont pas encore (lignes vides). */
    @PreAuthorize(LECTURE)
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
    @PreAuthorize(ECRITURE)
    @Transactional
    public RecetteResponse enregistrerRecette(Long platId, RecetteRequest req) {
        Article plat = exigerPlat(platId);
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
        log.info("Fiche technique enregistrée [plat={}, ingrédients={}]", plat.getCode(), req.lignes().size());
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId), cmpParArticle());
    }

    /**
     * Ajuste le prix de vente directement depuis la fiche technique : le
     * chef y voit déjà le coût de revient réel et la marge, c'est le bon
     * endroit pour corriger un prix devenu trop juste — plutôt que de
     * l'obliger à rouvrir la carte pour la même information.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public RecetteResponse modifierPrixVenteCarte(Long platId, BigDecimal prixVente) {
        Article plat = exigerPlat(platId);
        plat.setPrixVente(prixVente);
        articleRepository.save(plat);
        log.info("Prix de vente modifié [plat={}, prix={}]", plat.getCode(), prixVente);
        return construireRecette(plat, ligneRecetteRepository.findByPlatIdAvecProvision(platId), cmpParArticle());
    }

    @PreAuthorize(LECTURE)
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
    @PreAuthorize(ECRITURE)
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
    @PreAuthorize(ECRITURE)
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
            plat.getPrixVente(), lignesDto, coutEstime);
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
     * compte "non payee".
     */
    private TableResponse tableResponseAvecBadges(TableRestaurant t, List<Vente> ventes) {
        boolean nonPayee = ventes.stream().anyMatch(v -> !v.estReglee() || v.getStatut() == StatutVente.BROUILLON);
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
    // Provisions : vivres, épices, charbon...
    //
    // Stockées et consommées en interne (préparation des plats), jamais
    // vendues — contrairement à la carte, aucun tunnel de vente ne les
    // décrémente automatiquement : les sorties (utilisation en cuisine,
    // casse, péremption) se saisissent ici, sur un seul type générique avec
    // motif libre puisqu'aucune n'a d'incidence differente sur le stock.
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
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

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse creerProvision(ArticleRequest req) {
        exigerTypeProvision(req.type());
        ArticleRequest reqAvecComptes = new ArticleRequest(
            req.code(), req.libelle(), req.uniteMesure(), req.type(), req.categorie(), req.societe(), req.entrepotId(),
            genererCompteDedie(RACINE_COMPTE_STOCK_PROVISION, RoleCompteArticle.STOCK, req).getNumero(),
            genererCompteDedie(RACINE_COMPTE_CHARGE_PROVISION, RoleCompteArticle.VARIATION, req).getNumero(),
            req.compteProduitNumero(),
            genererCompteDedie(RACINE_COMPTE_ACHAT_PROVISION, RoleCompteArticle.ACHAT, req).getNumero(),
            req.prixVente(), req.prixAchat(), req.minerais(), req.soumisTva(), req.stockMin(), req.actif()
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

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse modifierProvision(Long id, ArticleRequest req) {
        exigerTypeProvision(req.type());
        Article existant = exigerArticleProvision(id);
        return stockService.modifierArticleInterne(id, completerParLExistant(existant, req));
    }

    @PreAuthorize(LECTURE)
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
    @PreAuthorize(LECTURE)
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
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<StockGrandLivreResponse> grandLivreProvisions(
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
        return lignes.stream()
            .map(s -> StockGrandLivreResponse.from(s,
                mouvementProvisionAnnulable(s.getMouvement(), appartenantAUneOperation)))
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
     * Annule une réception directe ou une sortie de provision saisie par
     * erreur : le stock est rétabli, la pièce de stock et, pour une
     * réception, la pièce d'achat sont extournées. Sans cela, une erreur de
     * saisie (100 kg reçus au lieu de 10) n'avait plus aucune voie de
     * correction : la Logistique refuse ces articles et la Comptabilité
     * refuse d'extourner seule une pièce de stock.
     */
    @PreAuthorize(ECRITURE)
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
    @PreAuthorize(LECTURE)
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
     * Réception d'une provision : achat immédiat, en deux écritures
     * indissociables.
     *
     * <p><b>1. Achat</b> — {@code D 601x/602x (compte d'achat dédié de la
     * provision) / C contrepartie} — puis <b>2. Entrée en stock</b> —
     * {@code D 331x / C 6032/6033}, exactement le même schéma que pour un
     * achat de boissons réglé par note de frais (voir {@code
     * StockService.entreesDepuisNoteFraisInterne}). Une version antérieure ne
     * produisait que la seconde écriture (via {@code
     * enregistrerEntreeRestaurantInterne}) : le bilan en ressortait juste,
     * mais l'achat (601/602) et sa variation de stock (603x) n'apparaissaient
     * jamais au compte de résultat — le même défaut que la StockService
     * documente déjà pour l'ancienne écriture directe des notes de frais.</p>
     *
     * <p>Le montant TOTAL (coût unitaire × quantité) est converti UNE SEULE
     * FOIS si le coût est coté en FC, jamais le seul coût unitaire : convertir
     * puis arrondir le coût unitaire à 2 décimales avant de le multiplier par
     * une grande quantité pouvait faire entrer la provision en stock à une
     * valeur significativement faussée (jusqu'à 12 % d'écart observé), et
     * tout coût unitaire sous ~14 FC/2 800 (le taux) s'arrondissait carrément
     * à zéro. Contrairement à un achat de boissons via note de frais (qui
     * peut attendre plusieurs jours l'approbation DFIN/DA avant règlement),
     * une réception de provision est immédiate : le taux du jour de la saisie
     * EST le taux de cette réception, il n'y a pas de délai à couvrir et donc
     * aucun écart de change à constater plus tard.</p>
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public void recevoirProvision(ProvisionEntreeRequest req) {
        Article article = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException("L'article " + article.getCode() + " n'est pas une provision");
        }
        if (article.getCompteAchat() == null || article.getCompteStock() == null || article.getCompteCharge() == null) {
            throw new IllegalArgumentException(
                "La provision " + article.getCode() + " n'a pas tous ses comptes dédiés "
                + "(achat, stock, variation de stock) : complétez sa fiche avant de réceptionner.");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        CompteOHADA contrepartie = compteRepository.findByNumero(req.compteContrepartieNumero())
            .orElseThrow(() -> new IllegalArgumentException(
                "Compte de contrepartie introuvable : " + req.compteContrepartieNumero()));
        // Une réception directe est un achat à crédit : sa contrepartie ne
        // peut être qu'un fournisseur. Créditer la caisse ou la banque d'ici
        // sortirait de l'argent au grand livre sans aucune opération à leur
        // journal — le solde du compte et celui du journal divergeraient sans
        // que le rapprochement le voie. Un achat payé comptant passe par une
        // note de frais, que la caisse règle.
        if (!contrepartie.getNumero().startsWith(RACINE_COMPTE_FOURNISSEURS)) {
            throw new IllegalArgumentException(
                "La contrepartie d'une réception doit être un compte fournisseur (" + RACINE_COMPTE_FOURNISSEURS
                + "x), pas " + contrepartie.getNumero() + " : un achat payé comptant passe par une note de "
                + "frais, que la caisse règle.");
        }
        LocalDate date = req.dateReception() == null ? LocalDate.now() : req.dateReception();
        User auteur = currentUser.requireUser();

        BigDecimal montantTotalUSD = conversionDevise
            .enDeviseBase(req.coutUnitaire().multiply(req.quantite()), req.devise())
            .montantBase();

        String libelle = "Achat " + article.getLibelle();
        PieceComptable pieceAchat = comptabilite.creerPieceInterne(JournalComptable.ACHATS, libelle, date, List.of(
            ecritureRestaurant(article.getCompteAchat(), montantTotalUSD, BigDecimal.ZERO, libelle, date),
            ecritureRestaurant(contrepartie, BigDecimal.ZERO, montantTotalUSD, libelle, date)
        ), auteur);

        MouvementStock reception = stockService.enregistrerEntreeAchatInterne(date, "Réception " + article.getLibelle(),
            new StockService.AchatMarchandise(article, entrepot, req.quantite(), montantTotalUSD,
                article.getCompteStock(), article.getCompteCharge()),
            auteur);
        // Rattachée à la réception : extournée avec elle à l'annulation, et
        // protégée d'une extourne isolée depuis la Comptabilité, qui ferait
        // disparaître l'achat en laissant la marchandise en stock.
        reception.setPieceAchat(pieceAchat);
    }

    /** Racine des comptes fournisseurs, seule contrepartie admise pour une réception directe. */
    private static final String RACINE_COMPTE_FOURNISSEURS = "40";

    private EcritureGrandLivre ecritureRestaurant(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                                  String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    /**
     * Sortie d'une provision : utilisation en cuisine, casse ou péremption —
     * un seul mécanisme, la raison n'a pas d'incidence sur le traitement.
     * Sortie de stock au CMP avec pièce comptable, même mécanique qu'une
     * vente (voir {@code StockService.enregistrerSortieVenteInterne}) ; la
     * garde de stock insuffisant s'applique ici aussi.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public void enregistrerSortieProvision(ProvisionSortieRequest req) {
        Article article = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException("L'article " + article.getCode() + " n'est pas une provision");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        LocalDate date = req.dateMouvement() == null ? LocalDate.now() : req.dateMouvement();
        String libelle = "Sortie provision — " + article.getLibelle()
            + (req.motif() != null && !req.motif().isBlank() ? " (" + req.motif() + ")" : "");

        stockService.enregistrerSortieVenteInterne(date, libelleMouvement(libelle), entrepot,
            List.of(new StockService.SortieVente(article, req.quantite())), currentUser.requireUser());
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
     * <p><b>Comptabilisée comme la part « coût » d'une vente.</b> Le plat
     * quitte le stock au coût de production moyen, par la même sortie que
     * pour une vente : D 736x Variations des stocks de produits finis /
     * C 361x stock du plat, au journal STOCK. La production stockée diminue
     * d'autant, si bien que le coût des ingrédients consommés reste en charge
     * sans produit en face — la perte apparaît au résultat. Même traitement
     * que la casse ou la péremption d'une boisson ; le motif sert au suivi.</p>
     *
     * <p>Un plat au coût de production nul (entré sans coût) sort sans
     * écriture : il n'y a rien à constater.</p>
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public SortiePlatResponse enregistrerSortiePlat(SortiePlatRequest req) {
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
        User auteur = currentUser.requireUser();

        String libelle = "Sortie de plat — " + req.motif().libelle() + " — " + plat.getLibelle()
            + (precision != null ? " (" + precision + ")" : "");
        MouvementStock mouvement = stockService.enregistrerSortieVenteInterne(date, libelleMouvement(libelle), entrepot,
            List.of(new StockService.SortieVente(plat, req.quantite())), auteur);
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

    @PreAuthorize(LECTURE)
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
    @PreAuthorize(ECRITURE)
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
    // Emballages : conditionnement et stock de vides
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<EmballageResponse> listerEmballages() {
        return emballageRepository.findAllAvecBoisson().stream().map(EmballageResponse::from).toList();
    }

    @PreAuthorize(ECRITURE)
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

    @PreAuthorize(ECRITURE)
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

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<MouvementEmballageResponse> listerMouvements(Long emballageId, LocalDate du, LocalDate au) {
        return mouvementRepository.rechercher(emballageId, du, au).stream()
            .map(MouvementEmballageResponse::from)
            .toList();
    }

    @PreAuthorize(ECRITURE)
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
        // elle-meme (pas seulement le contenant) : sortie de stock au CMP avec
        // la meme ecriture comptable qu'une sortie ordinaire (D compte de
        // charge de la boisson / C compte de stock) — casse, peremption et
        // offre gracieuse sont toutes des pertes de marchandise reelle, pas de
        // simples ajustements d'emballage.
        if (req.type() == TypeMouvementEmballage.CASSE_PLEINE || req.type() == TypeMouvementEmballage.PERIME
            || req.type() == TypeMouvementEmballage.CADEAU) {
            if (req.entrepotId() == null) {
                throw new IllegalArgumentException(
                    "L'entrepôt est obligatoire pour enregistrer la perte d'une boisson");
            }
            Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
            String motifLibelle = switch (req.type()) {
                case PERIME -> "Boisson périmée";
                case CADEAU -> "Boisson offerte (cadeau)";
                default -> "Casse (bouteille pleine)";
            };
            String libelle = motifLibelle + " — " + e.getArticleBoisson().getLibelle()
                + (req.motif() != null && !req.motif().isBlank() ? " (" + req.motif() + ")" : "");
            MouvementStock sortie = stockService.enregistrerSortieVenteInterne(
                m.getDateMouvement(), libelleMouvement(libelle), entrepot,
                List.of(new StockService.SortieVente(e.getArticleBoisson(), BigDecimal.valueOf(req.quantite()))),
                auteur);
            // Retrouvée à l'annulation de la perte, pour rétablir le stock.
            m.setMouvementStock(sortie);
        }

        return MouvementEmballageResponse.from(m);
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
    @PreAuthorize(ECRITURE)
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
    @PreAuthorize(LECTURE)
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
        Set<Long> idsCarte = articleRepository.findAll().stream()
            .filter(a -> TYPES_CARTE.contains(a.getType()))
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
        List<LigneVente> lignes = venteRepository.ligneVentesCartePeriode(du, au);

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
            if (!TYPES_CARTE.contains(a.getType()) || !a.isActif()) continue;
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

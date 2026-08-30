package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EmballageBoisson;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import com.mbsc.finapp.dto.logistique.ArticleRequest;
import com.mbsc.finapp.dto.logistique.ArticleResponse;
import com.mbsc.finapp.dto.logistique.EntrepotResponse;
import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;
import com.mbsc.finapp.dto.logistique.StockNiveauResponse;
import com.mbsc.finapp.dto.restaurant.EmballageRequest;
import com.mbsc.finapp.dto.restaurant.EmballageResponse;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageRequest;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageResponse;
import com.mbsc.finapp.dto.restaurant.ProvisionEntreeRequest;
import com.mbsc.finapp.dto.restaurant.ProvisionSortieRequest;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse.MouvementJourResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse.ProvisionStatResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.BoissonStatResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.PerteTypeResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.VentesJourResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.MouvementEmballageRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final StockService stockService;
    private final CurrentUserProvider currentUser;
    /** Résout le taux du jour pour les réceptions de provisions cotées en FC. */
    private final ConversionDeviseService conversionDevise;

    private static final String LECTURE =
        "hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN')";
    private static final String ECRITURE = "hasAnyRole('RESP_RESTAURANT', 'ADMIN')";

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

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse creerArticleCarte(ArticleRequest req) {
        exigerTypeCarte(req.type());
        ArticleResponse cree = stockService.creerArticleInterne(req);
        log.info("Article de carte créé [code={}, type={}]", cree.code(), cree.type());
        return cree;
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse modifierArticleCarte(Long id, ArticleRequest req) {
        exigerTypeCarte(req.type());
        exigerArticleDeLaCarte(id);
        return stockService.modifierArticleInterne(id, req);
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

    private void exigerArticleDeLaCarte(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (!TYPES_CARTE.contains(article.getType())) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'appartient pas à la carte du restaurant");
        }
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

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse creerProvision(ArticleRequest req) {
        exigerTypeProvision(req.type());
        ArticleResponse cree = stockService.creerArticleInterne(req);
        log.info("Provision créée [code={}]", cree.code());
        return cree;
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ArticleResponse modifierProvision(Long id, ArticleRequest req) {
        exigerTypeProvision(req.type());
        exigerArticleProvision(id);
        return stockService.modifierArticleInterne(id, req);
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

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<StockGrandLivreResponse> grandLivreProvisions(
            Long articleId, Long entrepotId, LocalDate du, LocalDate au) {
        return stockService.grandLivreStockInterne(articleId, entrepotId, du, au);
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

    private void exigerArticleProvision(Long id) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException(
                "L'article " + article.getCode() + " n'est pas une provision");
        }
    }

    /**
     * Réception d'une provision : entrée en stock valorisée, avec pièce
     * comptable — même mécanique que la réception de boissons, sans
     * l'échange de consigne qui ne s'applique pas à ces articles.
     *
     * <p>Si le coût est coté en FC, le taux du jour est résolu une seule fois
     * ici pour convertir en USD (devise de base du stock) avant stockage
     * définitif. Contrairement à un achat de boissons via note de frais (qui
     * peut attendre plusieurs jours l'approbation DFIN/DA avant règlement),
     * une réception de provision est immédiate : le taux du jour de la saisie
     * EST le taux de cette réception, il n'y a pas de délai à couvrir et donc
     * aucun écart de change à constater plus tard. Un changement de taux
     * ultérieur ne modifie jamais la valeur déjà enregistrée.</p>
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public void recevoirProvision(ProvisionEntreeRequest req) {
        Article article = articleRepository.findById(req.articleId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", req.articleId()));
        if (article.getType() != TypeArticle.PROVISION) {
            throw new IllegalArgumentException("L'article " + article.getCode() + " n'est pas une provision");
        }
        Entrepot entrepot = entrepotRepository.findById(req.entrepotId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId()));
        CompteOHADA contrepartie = compteRepository.findByNumero(req.compteContrepartieNumero())
            .orElseThrow(() -> new IllegalArgumentException(
                "Compte de contrepartie introuvable : " + req.compteContrepartieNumero()));
        LocalDate date = req.dateReception() == null ? LocalDate.now() : req.dateReception();
        BigDecimal coutUnitaireUSD = conversionDevise.enDeviseBase(req.coutUnitaire(), req.devise()).montantBase();

        stockService.enregistrerEntreeRestaurantInterne(
            date, "Réception " + article.getLibelle(), entrepot, contrepartie, article,
            req.quantite(), coutUnitaireUSD, currentUser.requireUser());
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

        stockService.enregistrerSortieVenteInterne(date, libelle, entrepot,
            List.of(new StockService.SortieVente(article, req.quantite())), currentUser.requireUser());
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
            stockService.enregistrerSortieVenteInterne(
                m.getDateMouvement(), libelle, entrepot,
                List.of(new StockService.SortieVente(e.getArticleBoisson(), BigDecimal.valueOf(req.quantite()))),
                auteur);
        }

        return MouvementEmballageResponse.from(m);
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
     * <p>Toutes les valorisations utilisent le coût moyen pondéré COURANT de
     * chaque boisson (celui affiché sur l'écran de stock), pas celui en
     * vigueur au moment exact de chaque mouvement — simplification déjà
     * pratiquée ailleurs dans ce module (voir l'écran Stock) et cohérente
     * avec elle.</p>
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
                qte.signum() == 0 ? BigDecimal.ZERO : valeur.divide(qte, 2, RoundingMode.HALF_UP));
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

        // ── Profit : marge brute des ventes moins la valeur des pertes ───
        BigDecimal coutDesVentes = BigDecimal.ZERO;
        for (var entry : venteParArticle.entrySet()) {
            BigDecimal cmup = cmupParArticle.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            coutDesVentes = coutDesVentes.add(cmup.multiply(entry.getValue()[0]));
        }
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
                emb == null ? null : emb.casiers()
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
        BigDecimal taux = vente.getTauxJournalier();
        return taux == null || taux.signum() <= 0 ? montant : montant.divide(taux, 2, RoundingMode.HALF_UP);
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

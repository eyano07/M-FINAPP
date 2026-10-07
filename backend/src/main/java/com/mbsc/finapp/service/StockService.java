package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;
import com.mbsc.finapp.dto.logistique.*;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.*;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Module Stock : articles, entrepôts, mouvements valorisés au coût moyen
 * pondéré (CMP) par couple (article, entrepôt). Inventaire permanent : chaque
 * entrée/sortie validée génère une pièce comptable OHADA.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ArticleRepository articleRepository;
    private final EntrepotRepository entrepotRepository;
    private final MouvementStockRepository mouvementRepository;
    private final StockNiveauRepository niveauRepository;
    private final StockGrandLivreRepository stockGrandLivreRepository;
    private final CompteOHADARepository compteRepository;
    /** Opérations métier propriétaires d'un mouvement — voir {@link #exigerAnnulableSeul}. */
    private final VenteRepository venteRepository;
    private final ProductionRepository productionRepository;
    private final CamionMineraiRepository camionRepository;
    private final MouvementEmballageRepository mouvementEmballageRepository;
    private final SortiePlatRepository sortiePlatRepository;
    private final ComptabiliteService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    /** Suivi de gestion des lots (date d'achat, fournisseur, FIFO ou CMP) — jamais comptable, voir sa javadoc. */
    private final LotStockService lotStock;

    // ---------------------------------------------------------------------
    // Articles
    // ---------------------------------------------------------------------

    // Le caissier est inclus en lecture : le catalogue (et sa disponibilite)
    // lui est necessaire pour saisir une vente.
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ArticleResponse> listerArticles() {
        return articleRepository.findAllWithComptes().stream().map(ArticleResponse::from).toList();
    }

    /**
     * Un article, à jour : l'écran de vente le relit au moment où il est
     * choisi, pour proposer le prix de vente en vigueur plutôt que celui du
     * catalogue chargé à l'ouverture de l'écran — qui peut rester ouvert
     * toute la journée à la caisse. Mêmes droits que {@link #listerArticles}.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public ArticleResponse consulterArticle(Long id) {
        return ArticleResponse.from(articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id)));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'ADMIN')")
    @Transactional
    public ArticleResponse creerArticle(ArticleRequest req) {
        if (articleRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un article avec le code " + req.code() + " existe déjà");
        }
        Article article = new Article();
        appliquerArticle(article, req);
        return ArticleResponse.from(articleRepository.save(article));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'ADMIN')")
    @Transactional
    public ArticleResponse modifierArticle(Long id, ArticleRequest req) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (!article.getCode().equals(req.code()) && articleRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un article avec le code " + req.code() + " existe déjà");
        }
        appliquerArticle(article, req);
        return ArticleResponse.from(articleRepository.save(article));
    }

    private void appliquerArticle(Article article, ArticleRequest req) {
        TypeArticle type = req.type() == null ? TypeArticle.MARCHANDISE : req.type();

        article.setCode(req.code());
        article.setLibelle(req.libelle());
        article.setUniteMesure(req.uniteMesure());
        article.setType(type);
        // Pertinente uniquement pour un plat ou une boisson (carte du restaurant) :
        // neutralisee pour tout autre type, plutot que de laisser trainer une
        // valeur qui n'aurait plus de sens si l'article change de type.
        boolean categorieApplicable = (type == TypeArticle.BOISSON || type == TypeArticle.PLAT)
            && StringUtils.hasText(req.categorie());
        article.setCategorie(categorieApplicable ? req.categorie().trim() : null);
        // La société (brasserie, fabricant, fournisseur) n'a de sens que pour une boisson de la carte.
        article.setSociete(type == TypeArticle.BOISSON && StringUtils.hasText(req.societe()) ? req.societe().trim() : null);
        article.setPrixVente(req.prixVente());
        article.setPrixAchat(req.prixAchat());
        article.setSoumisTva(req.soumisTva() == null || req.soumisTva());
        article.setStockMin(req.stockMin() == null ? BigDecimal.ZERO : req.stockMin());
        article.setActif(req.actif() == null || req.actif());
        article.setCompteProduit(resoudreCompteOuNull(req.compteProduitNumero()));

        // Un service n'a ni stock ni cout des ventes : on neutralise les
        // comptes (et l'entrepot) correspondants plutot que de laisser des
        // valeurs qui ne seront jamais mouvementees.
        if (type == TypeArticle.SERVICE) {
            article.setCompteStock(null);
            article.setCompteCharge(null);
            article.setCompteAchat(null);
            article.setStockMin(BigDecimal.ZERO);
            article.setEntrepot(null);
            // Un service n'a pas de stock : le suivi camion par camion n'a pas de sens.
            article.setMinerais(false);
        } else {
            article.setMinerais(Boolean.TRUE.equals(req.minerais()));
            article.setCompteStock(resoudreCompteOuNull(req.compteStockNumero()));
            article.setCompteCharge(resoudreCompteOuNull(req.compteChargeNumero()));
            article.setCompteAchat(resoudreCompteOuNull(req.compteAchatNumero()));
            // L'entrepot d'affectation n'est plus exige a la creation : la
            // quantite en stock se gere entrepot par entrepot depuis l'etat
            // du stock (StockNiveau), pas depuis une affectation unique
            // portee par l'article. Ce champ n'est lu par aucune logique
            // metier (les ventes choisissent leur propre entrepot ligne par
            // ligne) ; il reste possible a renseigner pour la tracabilite.
            article.setEntrepot(chargerEntrepotOuNull(req.entrepotId()));
        }
    }

    // ---------------------------------------------------------------------
    // Entrepôts
    // ---------------------------------------------------------------------

    // Le caissier est inclus en lecture, au meme titre que pour le catalogue
    // et l'etat du stock : une vente de marchandise impose de choisir
    // l'entrepot a decrementer (voir ventes/nouvelle.vue), donc sans cette
    // liste il ne peut pas aller au bout d'une vente.
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EntrepotResponse> listerEntrepots() {
        return entrepotRepository.findAllByOrderByCodeAsc().stream().map(EntrepotResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public EntrepotResponse creerEntrepot(EntrepotRequest req) {
        if (entrepotRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un entrepôt avec le code " + req.code() + " existe déjà");
        }
        Entrepot e = Entrepot.builder()
            .code(req.code())
            .nom(req.nom())
            .localisation(req.localisation())
            .actif(req.actif() == null || req.actif())
            .build();
        return EntrepotResponse.from(entrepotRepository.save(e));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public EntrepotResponse modifierEntrepot(Long id, EntrepotRequest req) {
        Entrepot e = entrepotRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", id));
        if (!e.getCode().equals(req.code()) && entrepotRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un entrepôt avec le code " + req.code() + " existe déjà");
        }
        e.setCode(req.code());
        e.setNom(req.nom());
        e.setLocalisation(req.localisation());
        e.setActif(req.actif() == null || req.actif());
        return EntrepotResponse.from(entrepotRepository.save(e));
    }

    // ---------------------------------------------------------------------
    // Mouvements de stock
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<MouvementResponse> listerMouvements() {
        // Une requête par propriétaire possible, pas une par mouvement : l'écran
        // ne propose « Annuler » que là où annuler() l'acceptera.
        java.util.Set<Long> rattaches = new java.util.HashSet<>(venteRepository.idsMouvementsValides());
        rattaches.addAll(productionRepository.idsMouvementsSortieValides());
        rattaches.addAll(productionRepository.idsMouvementsEntreeValides());
        rattaches.addAll(camionRepository.idsMouvementsValides());
        rattaches.addAll(mouvementEmballageRepository.idsMouvementsStockValides());
        java.util.Set<Long> restaurant = new java.util.HashSet<>(mouvementRepository.idsValidesModuleRestaurant());
        return mouvementRepository.findAllWithCreatedBy().stream()
            .map(m -> MouvementResponse.from(m, false,
                m.getStatut() == StatutMouvement.VALIDE && !rattaches.contains(m.getId()),
                restaurant.contains(m.getId())))
            .toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public MouvementResponse consulterMouvement(Long id) {
        return MouvementResponse.from(chargerMouvement(id));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public MouvementResponse creerMouvement(MouvementRequest req) {
        User auteur = currentUser.requireUser();
        validerLignes(req);

        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(req.type())
            .dateMouvement(req.dateMouvement() != null ? req.dateMouvement() : LocalDate.now())
            .libelle(req.libelle())
            .statut(StatutMouvement.BROUILLON)
            .compteContrepartie(resoudreCompteOuNull(req.compteContrepartieNumero()))
            .createdBy(auteur)
            .build();

        for (LigneMouvementRequest l : req.lignes()) {
            Article article = articleRepository.findById(l.articleId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Article", l.articleId()));
            exigerMarchandise(article);
            exigerHorsModuleDedie(article);
            BigDecimal cout = l.coutUnitaire() == null ? BigDecimal.ZERO : l.coutUnitaire();
            mouvement.addLigne(LigneMouvementStock.builder()
                .article(article)
                .entrepotSource(chargerEntrepotOuNull(l.entrepotSourceId()))
                .entrepotCible(chargerEntrepotOuNull(l.entrepotCibleId()))
                .quantite(l.quantite())
                .coutUnitaire(cout)
                .montant(cout.multiply(l.quantite()))
                .build());
        }

        MouvementStock saved = mouvementRepository.save(mouvement);
        log.info("Mouvement de stock créé [ref={}, type={}]", saved.getReference(), saved.getType());
        return MouvementResponse.from(saved);
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public MouvementResponse valider(Long id) {
        MouvementStock mouvement = chargerMouvement(id);
        if (mouvement.getStatut() != StatutMouvement.BROUILLON) {
            throw new TransitionInvalideException(
                "Seul un mouvement BROUILLON peut être validé (état actuel : " + mouvement.getStatut() + ")");
        }

        List<EcritureGrandLivre> ecrituresCompta = new ArrayList<>();
        for (LigneMouvementStock ligne : mouvement.getLignes()) {
            appliquerLigne(mouvement, ligne, ecrituresCompta);
        }

        // Génération de la pièce comptable si écritures présentes. Un transfert
        // n'en produit normalement aucune (même compte de stock des deux
        // côtés) : seulement s'il a fallu constater un écart de valorisation
        // à la sortie de l'entrepôt source (voir sortie()).
        if (!ecrituresCompta.isEmpty()) {
            String libelle = "Stock " + mouvement.getType() + " " + mouvement.getReference()
                + (StringUtils.hasText(mouvement.getLibelle()) ? " - " + mouvement.getLibelle() : "");
            PieceComptable piece = comptabilite.creerPieceInterne(
                JournalComptable.STOCK, libelle, mouvement.getDateMouvement(),
                ecrituresCompta, mouvement.getCreatedBy());
            mouvement.setPiece(piece);
        }

        mouvement.setStatut(StatutMouvement.VALIDE);
        log.info("Mouvement de stock validé [ref={}]", mouvement.getReference());
        return MouvementResponse.from(mouvement);
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public MouvementResponse annuler(Long id) {
        MouvementStock mouvement = chargerMouvement(id);
        if (mouvement.getStatut() != StatutMouvement.VALIDE) {
            throw new TransitionInvalideException(
                "Seul un mouvement VALIDE peut être annulé (état actuel : " + mouvement.getStatut() + ")");
        }
        exigerAnnulableSeul(mouvement);
        for (LigneMouvementStock ligne : mouvement.getLignes()) {
            exigerHorsModuleDedie(ligne.getArticle());
        }

        annulerMouvement(mouvement, currentUser.requireUser());
        log.info("Mouvement de stock annulé [ref={}]", mouvement.getReference());
        return MouvementResponse.from(mouvement);
    }

    /**
     * Refuse d'annuler seul un mouvement qui appartient à une opération
     * métier : la sortie de stock d'une vente, l'un des deux mouvements d'une
     * production, l'entrée en stock d'un camion de minerais, la sortie d'une
     * perte de boisson. Annulé ici, le
     * mouvement laisserait son opération dans un état incohérent (vente
     * toujours valide sans coût des ventes, production validée sans
     * portions...) : seul l'écran de l'opération sait la défaire entière.
     */
    public void exigerAnnulableSeul(MouvementStock mouvement) {
        List<String> ventes = venteRepository.referencesParMouvement(mouvement.getId());
        if (!ventes.isEmpty()) {
            throw new IllegalStateException(
                "Le mouvement " + mouvement.getReference() + " est la sortie de stock de la vente "
                + ventes.get(0) + " : annulez la vente, qui rétablira le stock avec elle.");
        }
        List<String> productions = productionRepository.referencesParMouvement(mouvement.getId());
        if (!productions.isEmpty()) {
            throw new IllegalStateException(
                "Le mouvement " + mouvement.getReference() + " appartient à la production "
                + productions.get(0) + " : annulez la production depuis l'écran Production du restaurant, "
                + "qui annule ses deux mouvements ensemble.");
        }
        List<String> camions = camionRepository.plaquesParMouvement(mouvement.getId());
        if (!camions.isEmpty()) {
            throw new IllegalStateException(
                "Le mouvement " + mouvement.getReference() + " est l'entrée en stock du camion "
                + camions.get(0) + " : supprimez le camion depuis l'écran Minerais.");
        }
        // Annulée seule, la sortie d'une perte laisserait la perte au journal
        // des vides et au tableau de bord : c'est la perte qui s'annule.
        if (mouvementEmballageRepository.existsByMouvementStockId(mouvement.getId())) {
            throw new IllegalStateException(
                "Le mouvement " + mouvement.getReference() + " est la sortie d'une perte de boisson : "
                + "annulez la perte depuis les mouvements de bouteilles vides du restaurant.");
        }
        // Même raison : annulée seule, la sortie laisserait la sortie de plat
        // (et son motif) dans l'historique comme si elle avait eu lieu.
        if (sortiePlatRepository.existsByMouvementStockId(mouvement.getId())) {
            throw new IllegalStateException(
                "Le mouvement " + mouvement.getReference() + " est une sortie de plat (perte, cadeau...) : "
                + "annulez-la depuis l'écran Sorties de plats du restaurant.");
        }
    }

    /**
     * Défait un mouvement validé : rejoue chaque ligne à l'envers sur les
     * niveaux de stock, extourne sa pièce de stock et, pour une réception
     * directe de provision, sa pièce d'achat. Si rejouer une sortie fait
     * apparaître un écart de valorisation (voir {@link #sortie}), il est
     * constaté par une pièce dédiée, rattachée au mouvement.
     *
     * <p>Passe par les variantes internes de la comptabilité :
     * l'autorisation est portée par l'appelant, l'extourne ne doit pas
     * exiger le rôle DFIN.</p>
     */
    private void annulerMouvement(MouvementStock mouvement, User auteur) {
        List<EcritureGrandLivre> ecarts = new ArrayList<>();
        for (LigneMouvementStock ligne : mouvement.getLignes()) {
            annulerLigne(mouvement, ligne, ecarts);
        }
        if (mouvement.getPiece() != null) {
            comptabilite.annulerInterne(mouvement.getPiece().getId(), auteur);
        }
        if (mouvement.getPieceAchat() != null) {
            comptabilite.annulerInterne(mouvement.getPieceAchat().getId(), auteur);
        }
        if (!ecarts.isEmpty()) {
            mouvement.setPieceEcartAnnulation(comptabilite.creerPieceInterne(
                JournalComptable.STOCK, "Écart de valorisation — annulation " + mouvement.getReference(),
                comptabilite.dateExtourne(mouvement.getDateMouvement()), ecarts, auteur));
        }
        mouvement.setStatut(StatutMouvement.ANNULE);
    }

    // ---------------------------------------------------------------------
    // Variantes internes (modules Vente et Restaurant)
    //
    // Sans @PreAuthorize : l'autorisation est portée par le service
    // appelant (VenteService, rôle CAISSIER), sur le même principe que
    // ComptabiliteService.annulerInterne. Ne jamais exposer via un
    // contrôleur.
    // ---------------------------------------------------------------------

    /**
     * Crée un article pour le compte d'un autre module métier (Restaurant).
     *
     * <p>Le module Restaurant tient sa propre carte mais n'a pas le module
     * LOGISTIQUE : il ne peut donc pas passer par {@link #creerArticle}, dont
     * le {@code @PreAuthorize} et le préfixe d'URL relèvent de la logistique.
     * Cette variante lui donne accès à la même mécanique — validation du code,
     * résolution des comptes, neutralisation des comptes non pertinents — sans
     * la dupliquer, l'autorisation étant portée par le service appelant.</p>
     */
    @Transactional
    public ArticleResponse creerArticleInterne(ArticleRequest req) {
        if (articleRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un article avec le code " + req.code() + " existe déjà");
        }
        Article article = new Article();
        appliquerArticle(article, req);
        return ArticleResponse.from(articleRepository.save(article));
    }

    /** Pendant de {@link #creerArticleInterne} pour la modification. */
    @Transactional
    public ArticleResponse modifierArticleInterne(Long id, ArticleRequest req) {
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
        if (!article.getCode().equals(req.code()) && articleRepository.existsByCode(req.code())) {
            throw new IllegalArgumentException("Un article avec le code " + req.code() + " existe déjà");
        }
        appliquerArticle(article, req);
        return ArticleResponse.from(articleRepository.save(article));
    }

    /** État du stock sans contrôle de rôle, pour un module métier qui porte le sien. */
    @Transactional(readOnly = true)
    public List<StockNiveauResponse> etatStockInterne() {
        return niveauRepository.findAllWithDetails().stream().map(StockNiveauResponse::from).toList();
    }

    /** Pendant de {@link #grandLivreStock} sans contrôle de rôle, pour un module métier qui porte le sien. */
    @Transactional(readOnly = true)
    public List<StockGrandLivreResponse> grandLivreStockInterne(Long articleId, Long entrepotId,
                                                                 LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();
        return stockGrandLivreRepository.rechercher(articleId, entrepotId, debut, fin).stream()
            .map(StockGrandLivreResponse::from)
            .toList();
    }

    /** Une ligne de sortie demandée par une vente : un article et sa quantité. */
    /**
     * @param coutImpose cout de sortie a retenir au lieu du cout moyen pondere,
     *        ou {@code null} pour le CMP habituel. Renseigne pour un camion de
     *        minerais : chaque chargement sort du stock a SON propre cout
     *        d'acquisition (prix d'achat + frais accessoires), le SYSCOHADA
     *        admettant l'identification specifique pour des biens non
     *        interchangeables. Sans cela, le CMP moyennerait des chargements
     *        de teneurs et de frais de route differents, et la marge camion par
     *        camion — la raison d'etre de ce suivi — serait faussee.
     */
    public record SortieVente(Article article, BigDecimal quantite, BigDecimal coutImpose) {
        public SortieVente(Article article, BigDecimal quantite) {
            this(article, quantite, null);
        }
    }

    /**
     * Crée et valide en une passe le mouvement de SORTIE correspondant aux
     * marchandises vendues : décrémente le stock au CMP, alimente le grand
     * livre de stock et génère la pièce du journal STOCK (coût des ventes).
     *
     * @return le mouvement validé, à rattacher à la vente
     */
    @Transactional
    public MouvementStock enregistrerSortieVenteInterne(LocalDate date, String libelle, Entrepot entrepot,
                                                        List<SortieVente> sorties, User auteur) {
        if (sorties == null || sorties.isEmpty()) {
            throw new IllegalArgumentException("Aucune marchandise à sortir du stock");
        }

        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(TypeMouvementStock.SORTIE)
            .dateMouvement(date)
            .libelle(libelle)
            .statut(StatutMouvement.BROUILLON)
            .createdBy(auteur)
            .build();

        for (SortieVente s : sorties) {
            exigerMarchandise(s.article());
            mouvement.addLigne(LigneMouvementStock.builder()
                .article(s.article())
                .entrepotSource(entrepot)
                .quantite(s.quantite())
                .coutUnitaire(BigDecimal.ZERO)   // remplacé par le CMP à la validation
                .montant(BigDecimal.ZERO)
                .build());
        }

        MouvementStock saved = mouvementRepository.save(mouvement);

        List<EcritureGrandLivre> ecrituresCompta = new ArrayList<>();
        // Les lignes sont creees dans l'ordre des sorties : l'index fait donc
        // correspondre chaque ligne au cout impose eventuel de sa sortie.
        List<LigneMouvementStock> lignesSortie = saved.getLignes();
        for (int i = 0; i < lignesSortie.size(); i++) {
            BigDecimal coutImpose = i < sorties.size() ? sorties.get(i).coutImpose() : null;
            appliquerLigne(saved, lignesSortie.get(i), ecrituresCompta, coutImpose);
        }
        if (!ecrituresCompta.isEmpty()) {
            PieceComptable piece = comptabilite.creerPieceInterne(
                JournalComptable.STOCK, libelle, date, ecrituresCompta, auteur);
            saved.setPiece(piece);
        }
        saved.setStatut(StatutMouvement.VALIDE);

        log.info("Sortie de stock pour vente [ref={}, lignes={}]", saved.getReference(), sorties.size());
        return saved;
    }

    /**
     * Crée et valide en une passe l'ENTRÉE en stock d'une réception de
     * boissons du module Restaurant : incrémente le niveau au coût unitaire
     * fourni, alimente le grand livre de stock et génère la pièce du journal
     * STOCK (D compte de stock de l'article / C contrepartie).
     *
     * <p>Distincte de {@link #entreesDepuisNoteFraisInterne}, qui ne crée
     * volontairement aucune pièce parce que la note de frais a déjà porté le
     * débit. Ici l'achat n'a pas d'autre support comptable, la pièce doit donc
     * bien être produite.</p>
     *
     * <p>Sans {@code @PreAuthorize} : l'autorisation est portée par le service
     * appelant (RestaurantService, rôle RESP_RESTAURANT). Ne jamais exposer
     * via un contrôleur.</p>
     */
    @Transactional
    public MouvementStock enregistrerEntreeRestaurantInterne(LocalDate date, String libelle, Entrepot entrepot,
                                                             CompteOHADA contrepartie, Article article,
                                                             BigDecimal quantite, BigDecimal coutUnitaire,
                                                             User auteur) {
        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(TypeMouvementStock.ENTREE)
            .dateMouvement(date)
            .libelle(libelle)
            .statut(StatutMouvement.BROUILLON)
            .compteContrepartie(contrepartie)
            .createdBy(auteur)
            .build();

        mouvement.addLigne(LigneMouvementStock.builder()
            .article(article)
            .entrepotCible(entrepot)
            .quantite(quantite)
            .coutUnitaire(coutUnitaire)
            .montant(coutUnitaire.multiply(quantite).setScale(2, RoundingMode.HALF_UP))
            .build());

        MouvementStock saved = mouvementRepository.save(mouvement);

        List<EcritureGrandLivre> ecrituresCompta = new ArrayList<>();
        for (LigneMouvementStock ligne : saved.getLignes()) {
            appliquerLigne(saved, ligne, ecrituresCompta);
        }
        if (!ecrituresCompta.isEmpty()) {
            PieceComptable piece = comptabilite.creerPieceInterne(
                JournalComptable.STOCK, libelle, date, ecrituresCompta, auteur);
            saved.setPiece(piece);
        }
        saved.setStatut(StatutMouvement.VALIDE);

        log.info("Entrée de stock restaurant [ref={}, article={}, qte={}]",
            saved.getReference(), article.getCode(), quantite);
        return saved;
    }

    /**
     * Une ligne d'entrée en stock issue d'un achat de marchandise réglé par note de frais.
     *
     * @param montant      coût d'entrée : prix HT plus la part des frais d'approche
     *                     (transport, manutention) qui revient à cet achat
     * @param montantAchat prix HT seul, sans les frais — voir StockNiveau.valeurAchat
     * @param fournisseur  bénéficiaire de la note de frais (traçabilité du lot — voir
     *                     {@link LotStockService}), {@code null} si non applicable
     */
    public record EntreeNoteFrais(Article article, Entrepot entrepot, BigDecimal quantite,
                                  BigDecimal montant, BigDecimal montantAchat, String fournisseur) {}

    /**
     * Entrée en stock issue d'un achat de marchandises réglé par note de
     * frais : met à jour le niveau de stock (CMP), le grand livre de stock,
     * et génère la pièce d'inventaire permanent — symétrique à
     * {@link #enregistrerEntreeAchatInterne} (achat saisi à la caisse).
     *
     * <p><b>Inventaire permanent SYSCOHADA révisé, deux écritures
     * indissociables.</b> Le règlement de la note impute déjà le compte
     * d'ACHAT de l'article (601x — voir {@code NoteFraisService.creerLignes})
     * face au compte de règlement (caisse, banque ou mobile money) : c'est la
     * première écriture, <b>D 601x / C règlement</b>. Cette méthode-ci
     * produit la SECONDE, celle qui constate l'entrée en stock :
     * <b>D 311x Marchandises / C 6031 Variations des stocks</b>, dans le
     * journal STOCK. Une version antérieure imputait directement le compte
     * de stock au règlement (D 311 / C règlement) et ne générait aucune
     * pièce ici, au motif que le débit était déjà porté ; le bilan en
     * ressortait juste, mais le compte de résultat était faux : ni l'achat
     * (601) ni sa variation de stock (6031) n'y apparaissaient jamais, alors
     * que c'est précisément leur différence qui forme le coût d'achat des
     * marchandises vendues.</p>
     */
    @Transactional
    public void entreesDepuisNoteFraisInterne(LocalDate date, String libelle, List<EntreeNoteFrais> entrees,
                                              PieceComptable pieceReglement, User auteur) {
        if (entrees == null || entrees.isEmpty()) {
            return;
        }

        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(TypeMouvementStock.ENTREE)
            .dateMouvement(date)
            .libelle(libelle)
            .statut(StatutMouvement.BROUILLON)
            .createdBy(auteur)
            .build();

        for (EntreeNoteFrais e : entrees) {
            exigerMarchandise(e.article());
            BigDecimal cout = e.quantite().signum() != 0
                ? e.montant().divide(e.quantite(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            mouvement.addLigne(LigneMouvementStock.builder()
                .article(e.article())
                .entrepotCible(e.entrepot())
                .quantite(e.quantite())
                .coutUnitaire(cout)
                .montant(e.montant())
                .montantAchat(e.montantAchat())
                .build());
        }

        MouvementStock saved = mouvementRepository.save(mouvement);
        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        for (int i = 0; i < saved.getLignes().size(); i++) {
            LigneMouvementStock ligne = saved.getLignes().get(i);
            EntreeNoteFrais e = entrees.get(i);
            StockNiveau niveau = entree(ligne.getArticle(), ligne.getEntrepotCible(),
                ligne.getQuantite(), ligne.getMontant(), ligne.montantAchatOuMontant());
            ecrireStockGrandLivre(ligne.getArticle(), ligne.getEntrepotCible(),
                saved, ligne.getQuantite(), BigDecimal.ZERO, niveau);
            ajouterEcriture(ecritures, compteStock(ligne.getArticle()), ligne.getMontant(), BigDecimal.ZERO, saved);
            ajouterEcriture(ecritures, compteCharge(ligne.getArticle()), BigDecimal.ZERO, ligne.getMontant(), saved);
            BigDecimal prixAchatUnite = e.quantite().signum() != 0
                ? e.montantAchat().divide(e.quantite(), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal prixTransportUnite = e.quantite().signum() != 0
                ? e.montant().subtract(e.montantAchat()).divide(e.quantite(), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            lotStock.enregistrerEntree(ligne.getArticle(), ligne.getEntrepotCible(), date, e.fournisseur(),
                ligne.getQuantite(), prixAchatUnite, prixTransportUnite, ligne);
        }
        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.STOCK, libelle, date, ecritures, auteur);
        saved.setPiece(piece);
        saved.setStatut(StatutMouvement.VALIDE);

        log.info("Entrée de stock pour note de frais [pieceReglement={}, pieceStock={}, lignes={}]",
            pieceReglement.getReference(), piece.getReference(), entrees.size());
    }

    public record AchatMarchandise(Article article, Entrepot entrepot, BigDecimal quantite,
                                   BigDecimal montant, CompteOHADA compteStock,
                                   CompteOHADA compteVariation) {}

    /**
     * Entrée en stock d'une marchandise achetée au comptant depuis la caisse
     * (bouton « Achat ») : applique le CMP, écrit le grand livre de stock et
     * génère la pièce d'inventaire permanent.
     *
     * <p><b>Inventaire permanent SYSCOHADA révisé.</b> L'achat lui-même
     * (D 601 Achats de marchandises / C 571 Caisse) est porté par la
     * transaction de caisse, comme tout décaissement — voir
     * {@code CaisseService.acheterMarchandise}. Cette méthode-ci ne produit
     * que la SECONDE écriture, celle qui constate l'entrée en stock :
     * <b>D 311 Marchandises / C 6031 Variations des stocks de
     * marchandises</b>. Les deux écritures sont indissociables : sans la
     * première, la sortie de trésorerie ne serait pas constatée ; sans la
     * seconde, la charge d'achat resterait au résultat alors que la
     * marchandise est encore en stock. C'est exactement l'image inverse du
     * déstockage sur vente (D 6031 / C 311, voir {@link #appliquerLigne}),
     * ce qui garantit que 6031 se solde en « coût d'achat des marchandises
     * vendues » à la clôture.</p>
     *
     * <p>Les deux comptes sont passés explicitement plutôt que lus sur
     * l'article : le caissier peut les corriger au moment de l'achat (une
     * marchandise peut entrer dans un autre compte de stock que celui
     * prévu par défaut), l'article ne fournissant que la valeur proposée.</p>
     */
    @Transactional
    public MouvementStock enregistrerEntreeAchatInterne(LocalDate date, String libelle,
                                                        AchatMarchandise achat, User auteur) {
        exigerMarchandise(achat.article());
        BigDecimal cout = achat.quantite().signum() != 0
            ? achat.montant().divide(achat.quantite(), 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(TypeMouvementStock.ENTREE)
            .dateMouvement(date)
            .libelle(libelle)
            .statut(StatutMouvement.BROUILLON)
            .compteContrepartie(achat.compteVariation())
            .createdBy(auteur)
            .build();
        mouvement.addLigne(LigneMouvementStock.builder()
            .article(achat.article())
            .entrepotCible(achat.entrepot())
            .quantite(achat.quantite())
            .coutUnitaire(cout)
            .montant(achat.montant())
            .montantAchat(achat.montant())
            .build());

        MouvementStock saved = mouvementRepository.save(mouvement);
        LigneMouvementStock ligne = saved.getLignes().get(0);
        StockNiveau niveau = entree(ligne.getArticle(), ligne.getEntrepotCible(),
            ligne.getQuantite(), ligne.getMontant(), ligne.getMontant());
        ecrireStockGrandLivre(ligne.getArticle(), ligne.getEntrepotCible(), saved,
            ligne.getQuantite(), BigDecimal.ZERO, niveau);

        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        ajouterEcriture(ecritures, achat.compteStock(), ligne.getMontant(), BigDecimal.ZERO, saved);
        ajouterEcriture(ecritures, achat.compteVariation(), BigDecimal.ZERO, ligne.getMontant(), saved);
        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.STOCK, libelle, date, ecritures, auteur);
        saved.setPiece(piece);
        saved.setStatut(StatutMouvement.VALIDE);

        log.info("Entrée de stock sur achat caisse [ref={}, article={}, qte={}]",
            saved.getReference(), achat.article().getCode(), achat.quantite());
        return saved;
    }

    /**
     * Défait un mouvement pour le compte d'un module métier (vente,
     * production, camion de minerais, correction d'une réception ou d'une
     * perte du restaurant) — voir {@link #annulerMouvement}. Sans effet sur
     * un mouvement absent ou déjà annulé.
     */
    @Transactional
    public void annulerMouvementInterne(MouvementStock mouvement, User auteur) {
        if (mouvement == null || mouvement.getStatut() != StatutMouvement.VALIDE) {
            return;
        }
        annulerMouvement(mouvement, auteur);
        log.info("Mouvement de stock annulé [ref={}]", mouvement.getReference());
    }

    /** Refuse un article SERVICE là où un bien stocké est attendu. */
    private void exigerMarchandise(Article article) {
        if (article.getType() == TypeArticle.SERVICE) {
            throw new IllegalArgumentException(
                "L'article \"" + article.getLibelle() + "\" est un service : il n'a pas de stock.");
        }
    }

    /**
     * Refuse qu'un mouvement créé ou annulé depuis la Logistique porte sur un
     * article PLAT/BOISSON/PROVISION : ces articles partagent les mêmes
     * tables de stock que le module Restaurant, mais leur cycle de vie
     * (échange de consigne, production, note de frais) n'est connu que de ce
     * module. Une sortie créée ici ne toucherait jamais le compteur de
     * bouteilles vides. Sans effet sur les variantes internes
     * (enregistrerSortieVenteInterne, entreesDepuisNoteFraisInterne...),
     * appelées par ces modules pour leurs propres articles.
     *
     * <p>L'administrateur en est exempté : c'est le dernier recours pour ce
     * que le module Restaurant n'annule pas lui-même — l'entrée en stock
     * issue du paiement d'une note de frais, notamment. À lui, alors,
     * d'ajuster le compteur de vides si la correction le demande. Même pour
     * lui, un mouvement qui appartient à une vente ou à une production reste
     * refusé (voir {@link #exigerAnnulableSeul}).</p>
     */
    private void exigerHorsModuleDedie(Article article) {
        if (article.getType().estGereParModuleDedie() && !estAdmin()) {
            throw new IllegalArgumentException(
                "L'article \"" + article.getLibelle() + "\" (" + article.getType()
                + ") est géré par le module Restaurant : utilisez ses propres écrans "
                + "(réception, sortie, perte, production, vente) plutôt que la Logistique.");
        }
    }

    private boolean estAdmin() {
        return currentUser.requireUser().getRoles().stream()
            .anyMatch(r -> r.getNom() == RoleType.ADMIN);
    }

    // ---------------------------------------------------------------------
    // Application de la valorisation CMP
    // ---------------------------------------------------------------------

    private void appliquerLigne(MouvementStock mouvement, LigneMouvementStock ligne,
                                List<EcritureGrandLivre> ecrituresCompta) {
        appliquerLigne(mouvement, ligne, ecrituresCompta, null);
    }

    /**
     * @param coutImpose cout unitaire de SORTIE a retenir au lieu du cout moyen
     *        pondere, ou {@code null} pour le CMP habituel — voir
     *        {@link SortieVente}. Sans effet sur une entree ou un transfert,
     *        dont le cout ne se deduit pas du stock existant.
     */
    private void appliquerLigne(MouvementStock mouvement, LigneMouvementStock ligne,
                                List<EcritureGrandLivre> ecrituresCompta, BigDecimal coutImpose) {
        Article article = ligne.getArticle();
        BigDecimal qte = ligne.getQuantite();

        switch (mouvement.getType()) {
            case ENTREE -> {
                Entrepot cible = exigerEntrepot(ligne.getEntrepotCible(), "entrepôt cible", mouvement);
                BigDecimal montant = ligne.getCoutUnitaire().multiply(qte).setScale(2, RoundingMode.HALF_UP);
                StockNiveau niveau = entree(article, cible, qte, montant, montant);
                ligne.setMontant(montant);
                ligne.setMontantAchat(montant);
                ecrireStockGrandLivre(article, cible, mouvement, qte, BigDecimal.ZERO, niveau);
                // Comptable : Débit compte stock / Crédit contrepartie
                ajouterEcriture(ecrituresCompta, compteStock(article), montant, BigDecimal.ZERO, mouvement);
                ajouterEcriture(ecrituresCompta, compteContrepartie(mouvement), BigDecimal.ZERO, montant, mouvement);
                // Suivi de gestion (traçabilité) : sans fournisseur ni frais connus ici,
                // à la différence d'un achat par note de frais — voir LotStockService.
                lotStock.enregistrerEntree(article, cible, mouvement.getDateMouvement(), null,
                    qte, ligne.getCoutUnitaire(), BigDecimal.ZERO, ligne);
            }
            case SORTIE -> {
                Entrepot source = exigerEntrepot(ligne.getEntrepotSource(), "entrepôt source", mouvement);
                // Identification specifique si un cout est impose (camion de
                // minerais), CMP sinon — voir SortieVente.coutImpose.
                BigDecimal cmp = coutImpose != null ? coutImpose : coutMoyen(article, source);
                BigDecimal montant = coutImpose != null
                    ? cmp.multiply(qte).setScale(2, RoundingMode.HALF_UP)
                    : montantSortieAuCmp(article, source, qte, cmp);
                BigDecimal partAchat = partAchatSortie(article, source, qte);
                StockNiveau niveau = sortie(article, source, qte, montant, partAchat, ecrituresCompta, mouvement);
                ligne.setMontantAchat(partAchat);
                ligne.setCoutUnitaire(cmp);
                ligne.setMontant(montant);
                ecrireStockGrandLivre(article, source, mouvement, BigDecimal.ZERO, qte, niveau);
                // Comptable : Débit compte charge / Crédit compte stock
                ajouterEcriture(ecrituresCompta, compteCharge(article), montant, BigDecimal.ZERO, mouvement);
                ajouterEcriture(ecrituresCompta, compteStock(article), BigDecimal.ZERO, montant, mouvement);
                // Suivi de gestion : déplète les lots en FIFO ou au prorata (résultat sans emploi ici, pas de transfert derrière).
                lotStock.consommer(article, source, qte, ligne);
            }
            case TRANSFERT -> {
                Entrepot source = exigerEntrepot(ligne.getEntrepotSource(), "entrepôt source", mouvement);
                Entrepot cible = exigerEntrepot(ligne.getEntrepotCible(), "entrepôt cible", mouvement);
                BigDecimal cmp = coutMoyen(article, source);
                // Un transfert qui vide la source emporte toute sa valeur :
                // le reliquat d'arrondi suit la marchandise au lieu de rester
                // sans elle dans l'entrepot de depart.
                BigDecimal montant = montantSortieAuCmp(article, source, qte, cmp);
                // La valeur hors frais suit la marchandise, comme sa valeur complète.
                BigDecimal partAchat = partAchatSortie(article, source, qte);
                StockNiveau niveauSource = sortie(article, source, qte, montant, partAchat, ecrituresCompta, mouvement);
                StockNiveau niveauCible = entree(article, cible, qte, montant, partAchat);
                ligne.setMontantAchat(partAchat);
                ligne.setCoutUnitaire(cmp);
                ligne.setMontant(montant);
                ecrireStockGrandLivre(article, source, mouvement, BigDecimal.ZERO, qte, niveauSource);
                ecrireStockGrandLivre(article, cible, mouvement, qte, BigDecimal.ZERO, niveauCible);
                // Pas d'écriture comptable pour un transfert interne, hormis
                // un éventuel écart de valorisation constaté par sortie().
                // Suivi de gestion : les lots déplétés à la source réapparaissent à l'identique en cible.
                lotStock.transferer(lotStock.consommer(article, source, qte, ligne), cible, ligne);
            }
        }
    }

    /**
     * Valeur d'une sortie au CMP. La sortie qui vide le niveau emporte
     * exactement la valeur restante, plutôt que CMP × quantité : chaque
     * sortie arrondit au centime, et sans cette règle les arrondis successifs
     * laissaient à la fin quelques centimes sans marchandise pour les porter.
     */
    private BigDecimal montantSortieAuCmp(Article article, Entrepot entrepot, BigDecimal qte, BigDecimal cmp) {
        StockNiveau niveau = niveau(article, entrepot);
        if (qte.compareTo(niveau.getQuantite()) == 0 && niveau.getValeurTotale().signum() >= 0) {
            return niveau.getValeurTotale();
        }
        return cmp.multiply(qte).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * @param ecarts reçoit les écritures d'un éventuel écart de valorisation
     *        (voir {@link #sortie}), à comptabiliser par l'appelant
     */
    private void annulerLigne(MouvementStock mouvement, LigneMouvementStock ligne, List<EcritureGrandLivre> ecarts) {
        Article article = ligne.getArticle();
        BigDecimal qte = ligne.getQuantite();
        BigDecimal montant = ligne.getMontant();
        // Suivi de gestion : restitue ce que cette ligne avait consommé et/ou retire ce
        // qu'elle avait créé — couvre ENTREE, SORTIE et TRANSFERT sans distinction (voir
        // LotStockService.annuler), donc placé une seule fois avant le switch comptable.
        lotStock.annuler(ligne);
        // Chaque contre-passation est journalisée dans le grand livre de stock
        // (piste d'audit complète, comme le Stock Ledger d'ERPNext).
        switch (mouvement.getType()) {
            case ENTREE -> {
                StockNiveau niveau = sortie(article, ligne.getEntrepotCible(), qte, montant,
                    ligne.montantAchatOuMontant(), ecarts, mouvement);
                ecrireStockGrandLivre(article, ligne.getEntrepotCible(), mouvement, BigDecimal.ZERO, qte, niveau);
            }
            case SORTIE -> {
                StockNiveau niveau = entree(article, ligne.getEntrepotSource(), qte, montant, ligne.montantAchatOuMontant());
                ecrireStockGrandLivre(article, ligne.getEntrepotSource(), mouvement, qte, BigDecimal.ZERO, niveau);
            }
            case TRANSFERT -> {
                StockNiveau niveauSource = entree(article, ligne.getEntrepotSource(), qte, montant, ligne.montantAchatOuMontant());
                StockNiveau niveauCible = sortie(article, ligne.getEntrepotCible(), qte, montant,
                    ligne.montantAchatOuMontant(), ecarts, mouvement);
                ecrireStockGrandLivre(article, ligne.getEntrepotSource(), mouvement, qte, BigDecimal.ZERO, niveauSource);
                ecrireStockGrandLivre(article, ligne.getEntrepotCible(), mouvement, BigDecimal.ZERO, qte, niveauCible);
            }
        }
    }

    /**
     * Entrée de stock : ajoute quantité et valeur, recalcule le CMP implicite.
     *
     * @param montant      coût d'entrée complet (frais d'approche compris)
     * @param montantAchat même entrée hors frais d'approche — égal à montant
     *                     quand il n'y en a pas (voir StockNiveau.valeurAchat)
     */
    private StockNiveau entree(Article article, Entrepot entrepot, BigDecimal qte, BigDecimal montant,
                               BigDecimal montantAchat) {
        StockNiveau niveau = niveau(article, entrepot);
        niveau.setQuantite(niveau.getQuantite().add(qte));
        niveau.setValeurTotale(niveau.getValeurTotale().add(montant));
        niveau.setValeurAchat(niveau.getValeurAchat().add(montantAchat));
        plafonnerValeurAchat(niveau);
        return niveauRepository.save(niveau);
    }

    /**
     * Part de la valeur hors frais qu'emporte une sortie de {@code qte} : au
     * prorata de la quantité, ou la totalité si la sortie vide le niveau — le
     * prix d'achat moyen des unités restantes ne bouge donc pas.
     */
    private BigDecimal partAchatSortie(Article article, Entrepot entrepot, BigDecimal qte) {
        StockNiveau niveau = niveau(article, entrepot);
        BigDecimal quantite = niveau.getQuantite();
        if (quantite.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (qte.compareTo(quantite) >= 0) {
            return niveau.getValeurAchat();
        }
        return niveau.getValeurAchat().multiply(qte).divide(quantite, 2, RoundingMode.HALF_UP);
    }

    /**
     * La valeur hors frais ne peut dépasser la valeur complète (les frais ne
     * sont jamais négatifs), ni rester sans marchandise, ni être négative.
     * Valeur de gestion : la corriger n'appelle aucune écriture.
     */
    private static void plafonnerValeurAchat(StockNiveau niveau) {
        BigDecimal v = niveau.getValeurAchat();
        if (niveau.getQuantite().signum() == 0 || v.signum() < 0) {
            v = BigDecimal.ZERO;
        } else if (v.compareTo(niveau.getValeurTotale()) > 0) {
            v = niveau.getValeurTotale();
        }
        niveau.setValeurAchat(v);
    }

    /**
     * Sortie de stock : retire la quantité et la valeur indiquées.
     *
     * <p>Un niveau ne peut garder de valeur sans marchandise, ni une valeur
     * négative. Une sortie peut pourtant y conduire : annulation d'une entrée
     * dont le stock a déjà été en partie consommé à un coût moyen mêlé (deux
     * productions à 20 puis 30, dix portions vendues à 25, première
     * production annulée : il reste 5 sans aucune portion), coût imposé,
     * arrondi. Effacer cet écart en silence désynchronisait le niveau de
     * stock du grand livre, qui gardait le montant ; refuser la sortie
     * bloquait l'annulation sans aucun moyen d'en sortir. L'écart est donc
     * constaté — voir {@link #constaterEcart} — et le niveau remis à zéro :
     * stock et grand livre restent égaux.</p>
     *
     * @param partAchat part de la valeur hors frais retirée avec la
     *        marchandise (voir {@link #partAchatSortie}, ou la part mémorisée
     *        sur la ligne annulée)
     * @param ecritures reçoit les écritures de l'écart éventuel
     * @param mouvement mouvement à l'origine de la sortie, pour le libellé
     */
    private StockNiveau sortie(Article article, Entrepot entrepot, BigDecimal qte, BigDecimal montant,
                               BigDecimal partAchat, List<EcritureGrandLivre> ecritures, MouvementStock mouvement) {
        StockNiveau niveau = niveau(article, entrepot);
        if (niveau.getQuantite().compareTo(qte) < 0) {
            throw new IllegalArgumentException(
                "Stock insuffisant pour l'article " + article.getCode()
                + " dans l'entrepôt " + entrepot.getCode()
                + " (disponible : " + niveau.getQuantite().toPlainString()
                + ", demandé : " + qte.toPlainString() + ")");
        }
        niveau.setQuantite(niveau.getQuantite().subtract(qte));
        BigDecimal nouvelleValeur = niveau.getValeurTotale().subtract(montant);
        boolean ecart = nouvelleValeur.signum() < 0
            || (niveau.getQuantite().signum() == 0 && nouvelleValeur.signum() != 0);
        if (ecart) {
            constaterEcart(article, entrepot, nouvelleValeur, ecritures, mouvement);
            nouvelleValeur = BigDecimal.ZERO;
        }
        niveau.setValeurTotale(nouvelleValeur);
        niveau.setValeurAchat(niveau.getValeurAchat().subtract(partAchat));
        plafonnerValeurAchat(niveau);
        return niveauRepository.save(niveau);
    }

    /**
     * Écritures d'un écart de valorisation, entre le compte de variation de
     * l'article et son compte de stock. Un écart positif est une valeur
     * restée en stock sans marchandise pour la porter : elle passe en charge
     * (D variation / C stock). Un écart négatif est une valeur retirée
     * au-delà de ce que le stock portait : elle est reprise (D stock /
     * C variation).
     */
    private void constaterEcart(Article article, Entrepot entrepot, BigDecimal ecart,
                                List<EcritureGrandLivre> ecritures, MouvementStock mouvement) {
        BigDecimal montant = ecart.abs();
        if (ecart.signum() > 0) {
            ajouterEcriture(ecritures, compteCharge(article), montant, BigDecimal.ZERO, mouvement);
            ajouterEcriture(ecritures, compteStock(article), BigDecimal.ZERO, montant, mouvement);
        } else {
            ajouterEcriture(ecritures, compteStock(article), montant, BigDecimal.ZERO, mouvement);
            ajouterEcriture(ecritures, compteCharge(article), BigDecimal.ZERO, montant, mouvement);
        }
        log.warn("Écart de valorisation constaté [article={}, entrepôt={}, mouvement={}, écart={}]",
            article.getCode(), entrepot.getCode(), mouvement.getReference(), ecart.toPlainString());
    }

    private StockNiveau niveau(Article article, Entrepot entrepot) {
        return niveauRepository.findByArticleAndEntrepot(article, entrepot)
            .orElseGet(() -> StockNiveau.builder()
                .article(article)
                .entrepot(entrepot)
                .quantite(BigDecimal.ZERO)
                .valeurTotale(BigDecimal.ZERO)
                .build());
    }

    /**
     * Ajuste la valorisation d'un niveau de stock sans en modifier la
     * quantité. Utilisé quand un écart de change reclasse a posteriori le
     * compte de stock d'un achat de marchandise réglé par note de frais
     * (voir {@code EcartChangeService}) : la contre-valeur en devise de base
     * change, pas le nombre d'unités reçues — sans cet ajustement, le grand
     * livre et le niveau de stock divergeraient durablement.
     *
     * <p>Écrit aussi une ligne dans {@code StockGrandLivre} (sans mouvement
     * associé) : sans elle, la valorisation affichée sur l'état du stock
     * change sans qu'aucune ligne de l'historique ne l'explique.</p>
     */
    @Transactional
    public void ajusterValeurStockInterne(Article article, Entrepot entrepot, BigDecimal delta, LocalDate date) {
        if (delta == null || delta.signum() == 0) {
            return;
        }
        StockNiveau niveau = niveau(article, entrepot);
        BigDecimal nouvelleValeur = niveau.getValeurTotale().add(delta);
        // Un ecart ne doit jamais faire passer la valorisation sous zero
        // (arrondi residuel si une sortie s'est intercalee entre-temps).
        niveau.setValeurTotale(nouvelleValeur.signum() < 0 ? BigDecimal.ZERO : nouvelleValeur);
        // Des frais (charges connexes d'un camion) : la valeur hors frais ne
        // bouge pas, hormis le plafond si la valeur complète est descendue.
        plafonnerValeurAchat(niveau);
        StockNiveau saved = niveauRepository.save(niveau);
        ecrireStockGrandLivre(article, entrepot, date, null, BigDecimal.ZERO, BigDecimal.ZERO, saved);
    }

    private BigDecimal coutMoyen(Article article, Entrepot entrepot) {
        StockNiveau niveau = niveau(article, entrepot);
        if (niveau.getQuantite().signum() == 0) {
            return BigDecimal.ZERO;
        }
        // Précision interne à 6 décimales : limite la dérive d'arrondi du CMP
        // sur les sorties successives (les montants restent arrondis à 2).
        return niveau.getValeurTotale().divide(niveau.getQuantite(), 6, RoundingMode.HALF_UP);
    }

    private void ecrireStockGrandLivre(Article article, Entrepot entrepot, MouvementStock mouvement,
                                       BigDecimal qteEntree, BigDecimal qteSortie, StockNiveau niveauApres) {
        ecrireStockGrandLivre(article, entrepot, mouvement.getDateMouvement(), mouvement, qteEntree, qteSortie, niveauApres);
    }

    /** Variante utilisée pour une écriture sans mouvement physique (ajustement de valorisation). */
    private void ecrireStockGrandLivre(Article article, Entrepot entrepot, LocalDate date, MouvementStock mouvement,
                                       BigDecimal qteEntree, BigDecimal qteSortie, StockNiveau niveauApres) {
        BigDecimal cmpApres = niveauApres.getQuantite().signum() == 0
            ? BigDecimal.ZERO
            : niveauApres.getValeurTotale().divide(niveauApres.getQuantite(), 2, RoundingMode.HALF_UP);
        stockGrandLivreRepository.save(StockGrandLivre.builder()
            .article(article)
            .entrepot(entrepot)
            .dateEcriture(date)
            .mouvement(mouvement)
            .qteEntree(qteEntree)
            .qteSortie(qteSortie)
            .qteApres(niveauApres.getQuantite())
            .valeurUnitaire(cmpApres)
            .valeurApres(niveauApres.getValeurTotale())
            .build());
    }

    // ---------------------------------------------------------------------
    // Lectures : état du stock & grand livre de stock
    // ---------------------------------------------------------------------

    // Le caissier consulte la disponibilite avant de vendre.
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'GEST_PATRIMOINE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<StockNiveauResponse> etatStock() {
        return niveauRepository.findAllWithDetails().stream().map(StockNiveauResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<StockGrandLivreResponse> grandLivreStock(Long articleId, Long entrepotId,
                                                         LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(2000, 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();
        return stockGrandLivreRepository.rechercher(articleId, entrepotId, debut, fin).stream()
            .map(StockGrandLivreResponse::from)
            .toList();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private MouvementStock chargerMouvement(Long id) {
        return mouvementRepository.findWithLignesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("MouvementStock", id));
    }

    private void validerLignes(MouvementRequest req) {
        if (req.lignes() == null || req.lignes().isEmpty()) {
            throw new IllegalArgumentException("Un mouvement doit contenir au moins une ligne");
        }
        for (LigneMouvementRequest l : req.lignes()) {
            if (l.quantite() == null || l.quantite().signum() <= 0) {
                throw new IllegalArgumentException("La quantité de chaque ligne doit être strictement positive");
            }
            switch (req.type()) {
                case ENTREE -> {
                    if (l.entrepotCibleId() == null) {
                        throw new IllegalArgumentException("Une entrée nécessite un entrepôt cible");
                    }
                }
                case SORTIE -> {
                    if (l.entrepotSourceId() == null) {
                        throw new IllegalArgumentException("Une sortie nécessite un entrepôt source");
                    }
                }
                case TRANSFERT -> {
                    if (l.entrepotSourceId() == null || l.entrepotCibleId() == null) {
                        throw new IllegalArgumentException("Un transfert nécessite un entrepôt source et un entrepôt cible");
                    }
                    if (l.entrepotSourceId().equals(l.entrepotCibleId())) {
                        throw new IllegalArgumentException("Les entrepôts source et cible doivent être différents");
                    }
                }
            }
        }
    }

    private Entrepot exigerEntrepot(Entrepot entrepot, String role, MouvementStock mouvement) {
        if (entrepot == null) {
            throw new IllegalArgumentException(
                "Le mouvement " + mouvement.getReference() + " requiert un " + role);
        }
        return entrepot;
    }

    private CompteOHADA compteStock(Article article) {
        if (article.getCompteStock() == null) {
            throw new IllegalStateException(
                "Aucun compte de stock défini pour l'article " + article.getCode());
        }
        return article.getCompteStock();
    }

    private CompteOHADA compteCharge(Article article) {
        if (article.getCompteCharge() == null) {
            throw new IllegalStateException(
                "Aucun compte de charge défini pour l'article " + article.getCode());
        }
        return article.getCompteCharge();
    }

    private CompteOHADA compteContrepartie(MouvementStock mouvement) {
        if (mouvement.getCompteContrepartie() == null) {
            throw new IllegalStateException(
                "Aucun compte de contrepartie défini pour l'entrée " + mouvement.getReference());
        }
        return mouvement.getCompteContrepartie();
    }

    private void ajouterEcriture(List<EcritureGrandLivre> liste, CompteOHADA compte,
                                 BigDecimal debit, BigDecimal credit, MouvementStock mouvement) {
        liste.add(EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(mouvement.getReference())
            .dateEcriture(mouvement.getDateMouvement())
            .build());
    }

    private CompteOHADA resoudreCompteOuNull(String numero) {
        if (!StringUtils.hasText(numero)) {
            return null;
        }
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    private Entrepot chargerEntrepotOuNull(Long id) {
        if (id == null) {
            return null;
        }
        return entrepotRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", id));
    }

    // Sans @PreAuthorize : simples resolutions d'identifiant, appelees par un
    // service qui porte deja sa propre garde (achat de marchandise a la caisse).

    @Transactional(readOnly = true)
    public Article articleParIdInterne(Long id) {
        return articleRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Article", id));
    }

    @Transactional(readOnly = true)
    public Entrepot entrepotParIdInterne(Long id) {
        return entrepotRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", id));
    }

    /**
     * Entrée en stock d'un chargement de minerais réceptionné par la
     * logistique : une unité de l'article (un camion), valorisée à son prix
     * d'achat.
     *
     * <p>Ne produit que l'écriture de stock <b>D 311x / C 6031</b> (journal
     * STOCK). La contrepartie de l'achat lui-même — <b>D 601x / C 4011
     * Fournisseurs</b> — est portée par {@code MineraiService.receptionner},
     * qui appelle cette méthode : la réception fait naître la dette
     * fournisseur, que la caisse soldera plus tard. Les deux écritures sont
     * indissociables, exactement comme pour un achat au comptant (voir
     * {@link #enregistrerEntreeAchatInterne}) — seule la contrepartie du
     * débit d'achat change (4011 au lieu de 571), le règlement étant
     * différé.</p>
     */
    @Transactional
    public MouvementStock enregistrerEntreeMineraiInterne(LocalDate date, String libelle, Article article,
                                                          Entrepot entrepot, BigDecimal prixAchat, User auteur) {
        exigerMarchandise(article);
        BigDecimal uneUnite = BigDecimal.ONE;

        MouvementStock mouvement = MouvementStock.builder()
            .reference(referenceGenerator.pourMouvementStock())
            .type(TypeMouvementStock.ENTREE)
            .dateMouvement(date)
            .libelle(libelle)
            .statut(StatutMouvement.BROUILLON)
            .compteContrepartie(compteCharge(article))
            .createdBy(auteur)
            .build();
        mouvement.addLigne(LigneMouvementStock.builder()
            .article(article)
            .entrepotCible(entrepot)
            .quantite(uneUnite)
            .coutUnitaire(prixAchat)
            .montant(prixAchat)
            .montantAchat(prixAchat)
            .build());

        MouvementStock saved = mouvementRepository.save(mouvement);
        LigneMouvementStock ligne = saved.getLignes().get(0);
        StockNiveau niveau = entree(article, entrepot, uneUnite, prixAchat, prixAchat);
        ecrireStockGrandLivre(article, entrepot, saved, uneUnite, BigDecimal.ZERO, niveau);

        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        ajouterEcriture(ecritures, compteStock(article), ligne.getMontant(), BigDecimal.ZERO, saved);
        ajouterEcriture(ecritures, compteCharge(article), BigDecimal.ZERO, ligne.getMontant(), saved);
        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.STOCK, libelle, date, ecritures, auteur);
        saved.setPiece(piece);
        saved.setStatut(StatutMouvement.VALIDE);
        return saved;
    }

}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.JournalComptable;
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
    private final ComptabiliteService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;

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
        article.setPrixVente(req.prixVente());
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
            article.setStockMin(BigDecimal.ZERO);
            article.setEntrepot(null);
        } else {
            article.setCompteStock(resoudreCompteOuNull(req.compteStockNumero()));
            article.setCompteCharge(resoudreCompteOuNull(req.compteChargeNumero()));
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
        return mouvementRepository.findAllWithCreatedBy().stream()
            .map(m -> MouvementResponse.from(m, false))
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

        // Génération de la pièce comptable (hors transfert) si écritures présentes.
        if (mouvement.getType() != TypeMouvementStock.TRANSFERT && !ecrituresCompta.isEmpty()) {
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

        // Annule l'effet de chaque ligne sur les niveaux de stock (mouvement inverse).
        for (LigneMouvementStock ligne : mouvement.getLignes()) {
            annulerLigne(mouvement, ligne);
        }

        // Extourne comptable si une pièce avait été générée. Passage par la
        // variante interne : l'autorisation est déjà portée par ce service
        // (LOGISTIQUE/ADMIN), l'extourne ne doit pas exiger le rôle DFIN.
        if (mouvement.getPiece() != null) {
            comptabilite.annulerInterne(mouvement.getPiece().getId(), currentUser.requireUser());
        }

        mouvement.setStatut(StatutMouvement.ANNULE);
        log.info("Mouvement de stock annulé [ref={}]", mouvement.getReference());
        return MouvementResponse.from(mouvement);
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
    public record SortieVente(Article article, BigDecimal quantite) {}

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
        for (LigneMouvementStock ligne : saved.getLignes()) {
            appliquerLigne(saved, ligne, ecrituresCompta);
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

    /** Une ligne d'entrée en stock issue d'un achat de marchandise réglé par note de frais. */
    public record EntreeNoteFrais(Article article, Entrepot entrepot, BigDecimal quantite, BigDecimal montantHt) {}

    /**
     * Entrée en stock issue d'un achat de marchandises réglé par note de
     * frais : met à jour le niveau de stock (CMP) et le grand livre de stock,
     * sans générer de pièce comptable.
     *
     * <p>Contrairement à une entrée ordinaire ({@link #valider}), la pièce
     * comptable n'est PAS créée ici : chaque ligne "achat de marchandise"
     * impute automatiquement le compte de stock de l'article (voir
     * {@code NoteFraisService.creerLignes}), donc le débit a déjà été porté
     * par la pièce de règlement de la note elle-même — en générer une
     * seconde dupliquerait la charge. Le mouvement est rattaché à cette
     * pièce de règlement pour rester traçable depuis le grand livre de
     * stock.</p>
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
            .piece(pieceReglement)
            .createdBy(auteur)
            .build();

        for (EntreeNoteFrais e : entrees) {
            exigerMarchandise(e.article());
            BigDecimal cout = e.quantite().signum() != 0
                ? e.montantHt().divide(e.quantite(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            mouvement.addLigne(LigneMouvementStock.builder()
                .article(e.article())
                .entrepotCible(e.entrepot())
                .quantite(e.quantite())
                .coutUnitaire(cout)
                .montant(e.montantHt())
                .build());
        }

        MouvementStock saved = mouvementRepository.save(mouvement);
        for (LigneMouvementStock ligne : saved.getLignes()) {
            StockNiveau niveau = entree(ligne.getArticle(), ligne.getEntrepotCible(),
                ligne.getQuantite(), ligne.getMontant());
            ecrireStockGrandLivre(ligne.getArticle(), ligne.getEntrepotCible(),
                saved, ligne.getQuantite(), BigDecimal.ZERO, niveau);
        }
        saved.setStatut(StatutMouvement.VALIDE);

        log.info("Entrée de stock pour note de frais [pieceReglement={}, lignes={}]",
            pieceReglement.getReference(), entrees.size());
    }

    /** Réintègre le stock et extourne la pièce d'un mouvement issu d'une vente annulée. */
    @Transactional
    public void annulerMouvementInterne(MouvementStock mouvement, User auteur) {
        if (mouvement == null || mouvement.getStatut() != StatutMouvement.VALIDE) {
            return;
        }
        for (LigneMouvementStock ligne : mouvement.getLignes()) {
            annulerLigne(mouvement, ligne);
        }
        if (mouvement.getPiece() != null) {
            comptabilite.annulerInterne(mouvement.getPiece().getId(), auteur);
        }
        mouvement.setStatut(StatutMouvement.ANNULE);
        log.info("Mouvement de stock annulé (vente) [ref={}]", mouvement.getReference());
    }

    /** Refuse un article SERVICE là où un bien stocké est attendu. */
    private void exigerMarchandise(Article article) {
        if (article.getType() == TypeArticle.SERVICE) {
            throw new IllegalArgumentException(
                "L'article \"" + article.getLibelle() + "\" est un service : il n'a pas de stock.");
        }
    }

    // ---------------------------------------------------------------------
    // Application de la valorisation CMP
    // ---------------------------------------------------------------------

    private void appliquerLigne(MouvementStock mouvement, LigneMouvementStock ligne,
                                List<EcritureGrandLivre> ecrituresCompta) {
        Article article = ligne.getArticle();
        BigDecimal qte = ligne.getQuantite();

        switch (mouvement.getType()) {
            case ENTREE -> {
                Entrepot cible = exigerEntrepot(ligne.getEntrepotCible(), "entrepôt cible", mouvement);
                BigDecimal montant = ligne.getCoutUnitaire().multiply(qte).setScale(2, RoundingMode.HALF_UP);
                StockNiveau niveau = entree(article, cible, qte, montant);
                ligne.setMontant(montant);
                ecrireStockGrandLivre(article, cible, mouvement, qte, BigDecimal.ZERO, niveau);
                // Comptable : Débit compte stock / Crédit contrepartie
                ajouterEcriture(ecrituresCompta, compteStock(article), montant, BigDecimal.ZERO, mouvement);
                ajouterEcriture(ecrituresCompta, compteContrepartie(mouvement), BigDecimal.ZERO, montant, mouvement);
            }
            case SORTIE -> {
                Entrepot source = exigerEntrepot(ligne.getEntrepotSource(), "entrepôt source", mouvement);
                BigDecimal cmp = coutMoyen(article, source);
                BigDecimal montant = cmp.multiply(qte).setScale(2, RoundingMode.HALF_UP);
                StockNiveau niveau = sortie(article, source, qte, montant);
                ligne.setCoutUnitaire(cmp);
                ligne.setMontant(montant);
                ecrireStockGrandLivre(article, source, mouvement, BigDecimal.ZERO, qte, niveau);
                // Comptable : Débit compte charge / Crédit compte stock
                ajouterEcriture(ecrituresCompta, compteCharge(article), montant, BigDecimal.ZERO, mouvement);
                ajouterEcriture(ecrituresCompta, compteStock(article), BigDecimal.ZERO, montant, mouvement);
            }
            case TRANSFERT -> {
                Entrepot source = exigerEntrepot(ligne.getEntrepotSource(), "entrepôt source", mouvement);
                Entrepot cible = exigerEntrepot(ligne.getEntrepotCible(), "entrepôt cible", mouvement);
                BigDecimal cmp = coutMoyen(article, source);
                BigDecimal montant = cmp.multiply(qte).setScale(2, RoundingMode.HALF_UP);
                StockNiveau niveauSource = sortie(article, source, qte, montant);
                StockNiveau niveauCible = entree(article, cible, qte, montant);
                ligne.setCoutUnitaire(cmp);
                ligne.setMontant(montant);
                ecrireStockGrandLivre(article, source, mouvement, BigDecimal.ZERO, qte, niveauSource);
                ecrireStockGrandLivre(article, cible, mouvement, qte, BigDecimal.ZERO, niveauCible);
                // Pas d'écriture comptable pour un transfert interne.
            }
        }
    }

    private void annulerLigne(MouvementStock mouvement, LigneMouvementStock ligne) {
        Article article = ligne.getArticle();
        BigDecimal qte = ligne.getQuantite();
        BigDecimal montant = ligne.getMontant();
        // Chaque contre-passation est journalisée dans le grand livre de stock
        // (piste d'audit complète, comme le Stock Ledger d'ERPNext).
        switch (mouvement.getType()) {
            case ENTREE -> {
                StockNiveau niveau = sortie(article, ligne.getEntrepotCible(), qte, montant);
                ecrireStockGrandLivre(article, ligne.getEntrepotCible(), mouvement, BigDecimal.ZERO, qte, niveau);
            }
            case SORTIE -> {
                StockNiveau niveau = entree(article, ligne.getEntrepotSource(), qte, montant);
                ecrireStockGrandLivre(article, ligne.getEntrepotSource(), mouvement, qte, BigDecimal.ZERO, niveau);
            }
            case TRANSFERT -> {
                StockNiveau niveauSource = entree(article, ligne.getEntrepotSource(), qte, montant);
                StockNiveau niveauCible = sortie(article, ligne.getEntrepotCible(), qte, montant);
                ecrireStockGrandLivre(article, ligne.getEntrepotSource(), mouvement, qte, BigDecimal.ZERO, niveauSource);
                ecrireStockGrandLivre(article, ligne.getEntrepotCible(), mouvement, BigDecimal.ZERO, qte, niveauCible);
            }
        }
    }

    /** Entrée de stock : ajoute quantité et valeur, recalcule le CMP implicite. */
    private StockNiveau entree(Article article, Entrepot entrepot, BigDecimal qte, BigDecimal montant) {
        StockNiveau niveau = niveau(article, entrepot);
        niveau.setQuantite(niveau.getQuantite().add(qte));
        niveau.setValeurTotale(niveau.getValeurTotale().add(montant));
        return niveauRepository.save(niveau);
    }

    /** Sortie de stock : retire la quantité et la valeur correspondante au CMP. */
    private StockNiveau sortie(Article article, Entrepot entrepot, BigDecimal qte, BigDecimal montant) {
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
        // Évite une valeur résiduelle négative due aux arrondis quand le stock tombe à 0.
        if (niveau.getQuantite().signum() == 0 || nouvelleValeur.signum() < 0) {
            nouvelleValeur = BigDecimal.ZERO.max(nouvelleValeur);
            if (niveau.getQuantite().signum() == 0) {
                nouvelleValeur = BigDecimal.ZERO;
            }
        }
        niveau.setValeurTotale(nouvelleValeur);
        return niveauRepository.save(niveau);
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
}

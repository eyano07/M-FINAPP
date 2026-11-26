package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.Production;
import com.mbsc.finapp.domain.SortiePlat;
import com.mbsc.finapp.domain.StockGrandLivre;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.dto.restaurant.LigneProductionRequest;
import com.mbsc.finapp.dto.restaurant.PlatPerimeResponse;
import com.mbsc.finapp.dto.restaurant.ProductionRequest;
import com.mbsc.finapp.dto.restaurant.ProductionResponse;
import com.mbsc.finapp.dto.restaurant.RecetteRequest;
import com.mbsc.finapp.dto.restaurant.RecetteResponse;
import com.mbsc.finapp.dto.restaurant.RetraitPerimesRequest;
import com.mbsc.finapp.dto.restaurant.SortiePlatResponse;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.repository.ProductionRepository;
import com.mbsc.finapp.repository.SortiePlatRepository;
import com.mbsc.finapp.repository.StockGrandLivreRepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plats périmés : la durée de conservation de la fiche technique donne à chaque production sa date d'expiration ; passée
 * cette date, les portions encore en stock sont périmées — repérées, retirées du stock en perte, et plus vendables.
 */
class PlatsPerimesTest {

    private static final LocalDate AUJOURDHUI = LocalDate.now();

    private ArticleRepository articles;
    private EntrepotRepository entrepots;
    private StockNiveauRepository niveaux;
    private ProductionRepository productions;
    private StockGrandLivreRepository grandLivre;
    private SortiePlatRepository sortiesPlats;
    private ParametresRestaurantRepository parametres;
    private CurrentUserProvider utilisateur;
    private User connecte;
    private StockService stock;
    private ReferenceGenerator references;
    private RestaurantService service;
    private Article omelette;
    private Entrepot entrepot;

    @BeforeEach
    void preparer() throws Exception {
        // RestaurantService a une trentaine de dépendances : chacune est simulée, seules celles du test sont réglées.
        Constructor<?> constructeur = RestaurantService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (RestaurantService) constructeur.newInstance(arguments);
        articles = trouver(arguments, ArticleRepository.class);
        entrepots = trouver(arguments, EntrepotRepository.class);
        niveaux = trouver(arguments, StockNiveauRepository.class);
        productions = trouver(arguments, ProductionRepository.class);
        grandLivre = trouver(arguments, StockGrandLivreRepository.class);
        sortiesPlats = trouver(arguments, SortiePlatRepository.class);
        parametres = trouver(arguments, ParametresRestaurantRepository.class);
        // Chaque motif de sortie de plat a son compte (V121) : tous existent au plan comptable.
        when(trouver(arguments, CompteOHADARepository.class).findByNumero(anyString()))
            .thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(i.getArgument(0)).build()));
        stock = trouver(arguments, StockService.class);
        references = trouver(arguments, ReferenceGenerator.class);
        utilisateur = trouver(arguments, CurrentUserProvider.class);

        connecte = User.builder().id(5L).prenom("Papy").nom("Penze").email("rest@mbsc.cd").build();
        when(utilisateur.requireUser()).thenReturn(connecte);
        omelette = Article.builder().id(10L).code("PLAT-OMEL").libelle("Omelette").uniteMesure("portion")
            .type(TypeArticle.PLAT).compteCharge(CompteOHADA.builder().numero("73611").build())
            .compteStock(CompteOHADA.builder().numero("36111").build()).build();
        entrepot = Entrepot.builder().id(1L).code("ENT-01").nom("Entrepôt Principal").build();
        when(articles.findById(10L)).thenReturn(Optional.of(omelette));
        when(entrepots.findById(1L)).thenReturn(Optional.of(entrepot));
        when(sortiesPlats.save(any(SortiePlat.class))).thenAnswer(i -> {
            SortiePlat s = i.getArgument(0);
            s.setId(99L);
            return s;
        });
    }

    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return type.cast(Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow());
    }

    private static Production production(String reference, long mouvementEntreeId, int ilYaJours, Integer expireDansJours,
                                         String quantite, Article plat, Entrepot entrepot) {
        return Production.builder().reference(reference).plat(plat).entrepot(entrepot)
            .dateProduction(AUJOURDHUI.minusDays(ilYaJours))
            .dateExpiration(expireDansJours == null ? null : AUJOURDHUI.plusDays(expireDansJours))
            .mouvementEntree(MouvementStock.builder().id(mouvementEntreeId).build())
            .quantite(new BigDecimal(quantite)).build();
    }

    /** Une ligne du grand livre de stock du plat : une entrée (quantité positive) ou une sortie (négative). */
    private StockGrandLivre ligne(long mouvementId, StatutMouvement statut, int ilYaJours, String quantite) {
        BigDecimal q = new BigDecimal(quantite);
        return StockGrandLivre.builder().article(omelette).entrepot(entrepot)
            .dateEcriture(AUJOURDHUI.minusDays(ilYaJours))
            .mouvement(MouvementStock.builder().id(mouvementId).statut(statut).build())
            .qteEntree(q.signum() > 0 ? q : BigDecimal.ZERO).qteSortie(q.signum() < 0 ? q.negate() : BigDecimal.ZERO).build();
    }

    private void historique(StockGrandLivre... lignes) {
        when(grandLivre.rechercher(eq(10L), eq(1L), any(), any())).thenReturn(List.of(lignes));
    }

    /**
     * 12 omelettes en stock, valant 24. Production n° 1 d'il y a 3 jours (8 portions, expirée hier) : 3 vendues avant son
     * expiration. Production n° 2 d'aujourd'hui (10 portions, expire demain) : 3 vendues aujourd'hui, qui prennent les
     * portions bonnes. Reste donc 5 portions périmées de la production n° 1 et 7 de la production n° 2. Une vente annulée
     * (original et contre-passation) ne compte pas.
     */
    private void douzeOmelettesDontCinqPerimees() {
        when(niveaux.findAllWithDetails()).thenReturn(List.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("12")).valeurTotale(new BigDecimal("24")).build()));
        when(productions.findValideesDuPlatDansEntrepot(10L, 1L)).thenReturn(List.of(
            production("PRD-2026-000002", 102L, 0, 1, "10", omelette, entrepot),
            production("PRD-2026-000001", 101L, 3, -1, "8", omelette, entrepot)));
        historique(
            ligne(101L, StatutMouvement.VALIDE, 3, "8"),
            ligne(201L, StatutMouvement.VALIDE, 2, "-3"),
            ligne(102L, StatutMouvement.VALIDE, 0, "10"),
            ligne(203L, StatutMouvement.ANNULE, 0, "-4"),
            ligne(203L, StatutMouvement.ANNULE, 0, "4"),
            ligne(202L, StatutMouvement.VALIDE, 0, "-3"));
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            List<StockService.SortieVente> sorties = i.getArgument(3);
            BigDecimal montant = sorties.get(0).quantite().multiply(new BigDecimal("2"));
            MouvementStock m = MouvementStock.builder().reference("MS-2026-000050").build();
            m.addLigne(LigneMouvementStock.builder().article(omelette).quantite(sorties.get(0).quantite()).montant(montant).build());
            return m;
        });
    }

    // ---------------------------------------------------------------------
    // La durée de conservation, dans la fiche technique
    // ---------------------------------------------------------------------

    @Test
    void laFicheEnregistreLaDureeDeConservationDuPlat() {
        RecetteResponse reponse = service.enregistrerRecette(10L, new RecetteRequest(List.of(), 3));

        assertThat(omelette.getDureeConservationJours()).isEqualTo(3);
        assertThat(reponse.dureeConservationJours()).isEqualTo(3);
    }

    @Test
    void uneFicheSansDureeEffaceLaDureeDeConservation() {
        omelette.setDureeConservationJours(3);

        RecetteResponse reponse = service.enregistrerRecette(10L, new RecetteRequest(List.of(), null));

        assertThat(omelette.getDureeConservationJours()).isNull();
        assertThat(reponse.dureeConservationJours()).isNull();
    }

    @Test
    void uneProductionFixeLaDateDExpirationDeSesPortions() {
        omelette.setDureeConservationJours(2);
        Article oeuf = Article.builder().id(20L).code("DEJ-01").libelle("Oeuf").type(TypeArticle.PROVISION).build();
        when(articles.findById(20L)).thenReturn(Optional.of(oeuf));
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            MouvementStock m = MouvementStock.builder().reference("MS-2026-000060").build();
            m.addLigne(LigneMouvementStock.builder().article(oeuf).quantite(new BigDecimal("6")).montant(new BigDecimal("3")).build());
            return m;
        });
        when(stock.enregistrerEntreeRestaurantInterne(any(), anyString(), any(), any(), any(), any(), any(), any()))
            .thenReturn(MouvementStock.builder().reference("MS-2026-000061").build());
        when(productions.save(any(Production.class))).thenAnswer(i -> i.getArgument(0));
        when(references.pourProduction()).thenReturn("PRD-2026-000009");

        ProductionResponse reponse = service.produire(new ProductionRequest(10L, new BigDecimal("6"), 1L,
            LocalDate.of(2026, 10, 11), List.of(new LigneProductionRequest(20L, new BigDecimal("6")))));

        assertThat(reponse.dateProduction()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(reponse.dateExpiration()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void sansDureeDeConservationLesPortionsNExpirentJamais() {
        Article oeuf = Article.builder().id(20L).code("DEJ-01").libelle("Oeuf").type(TypeArticle.PROVISION).build();
        when(articles.findById(20L)).thenReturn(Optional.of(oeuf));
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            MouvementStock m = MouvementStock.builder().reference("MS-2026-000060").build();
            m.addLigne(LigneMouvementStock.builder().article(oeuf).quantite(new BigDecimal("6")).montant(new BigDecimal("3")).build());
            return m;
        });
        when(stock.enregistrerEntreeRestaurantInterne(any(), anyString(), any(), any(), any(), any(), any(), any()))
            .thenReturn(MouvementStock.builder().reference("MS-2026-000061").build());
        when(productions.save(any(Production.class))).thenAnswer(i -> i.getArgument(0));
        when(references.pourProduction()).thenReturn("PRD-2026-000009");

        ProductionResponse reponse = service.produire(new ProductionRequest(10L, new BigDecimal("6"), 1L,
            LocalDate.of(2026, 10, 11), List.of(new LigneProductionRequest(20L, new BigDecimal("6")))));

        assertThat(reponse.dateExpiration()).isNull();
    }

    // ---------------------------------------------------------------------
    // Repérer les plats périmés
    // ---------------------------------------------------------------------

    @Test
    void lesPortionsNonVenduesDontLaProductionAExpireSontPerimees() {
        douzeOmelettesDontCinqPerimees();

        List<PlatPerimeResponse> perimes = service.listerPlatsPerimes();

        assertThat(perimes).hasSize(1);
        PlatPerimeResponse p = perimes.get(0);
        assertThat(p.platCode()).isEqualTo("PLAT-OMEL");
        assertThat(p.entrepotCode()).isEqualTo("ENT-01");
        assertThat(p.quantitePerimee()).isEqualByComparingTo("5");
        assertThat(p.coutMoyen()).isEqualByComparingTo("2");
        assertThat(p.valeur()).isEqualByComparingTo("10");
        assertThat(p.perimeDepuis()).isEqualTo(AUJOURDHUI.minusDays(1));
        assertThat(p.lots()).hasSize(1);
        assertThat(p.lots().get(0).productionReference()).isEqualTo("PRD-2026-000001");
        assertThat(p.lots().get(0).quantite()).isEqualByComparingTo("5");
    }

    @Test
    void unRetraitPerimeDejaFaitNeReapparaitPas() {
        douzeOmelettesDontCinqPerimees();
        // Les 5 périmées ont été retirées aujourd'hui (sortie « Périmé » n° 204) : il reste 7 portions, toutes bonnes.
        when(niveaux.findAllWithDetails()).thenReturn(List.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("7")).valeurTotale(new BigDecimal("14")).build()));
        historique(
            ligne(101L, StatutMouvement.VALIDE, 3, "8"),
            ligne(201L, StatutMouvement.VALIDE, 2, "-3"),
            ligne(102L, StatutMouvement.VALIDE, 0, "10"),
            ligne(202L, StatutMouvement.VALIDE, 0, "-3"),
            ligne(204L, StatutMouvement.VALIDE, 0, "-5"));
        when(sortiesPlats.idsMouvementsParMotif(MotifSortiePlat.PERIME)).thenReturn(List.of(204L));

        assertThat(service.listerPlatsPerimes()).isEmpty();
    }

    @Test
    void ceQuiNEstPasUnPlatOuQuiEstVideNEstJamaisListe() {
        Article primus = Article.builder().id(11L).code("BRAC-PRIM-55CL").libelle("Primus").type(TypeArticle.BOISSON).build();
        when(niveaux.findAllWithDetails()).thenReturn(List.of(
            StockNiveau.builder().article(primus).entrepot(entrepot).quantite(new BigDecimal("50")).valeurTotale(new BigDecimal("90")).build(),
            StockNiveau.builder().article(omelette).entrepot(entrepot).quantite(BigDecimal.ZERO).valeurTotale(BigDecimal.ZERO).build()));

        assertThat(service.listerPlatsPerimes()).isEmpty();
        verify(productions, never()).findValideesDuPlatDansEntrepot(anyLong(), anyLong());
    }

    @Test
    void lePerimeNeDepasseJamaisLeStock() {
        douzeOmelettesDontCinqPerimees();
        // Un stock plus petit que ce que l'historique laisse supposer (historique incomplet) : le stock fait foi.
        when(niveaux.findAllWithDetails()).thenReturn(List.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("3")).valeurTotale(new BigDecimal("6")).build()));

        assertThat(service.listerPlatsPerimes().get(0).quantitePerimee()).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------------------
    // Retirer du stock les portions périmées
    // ---------------------------------------------------------------------

    @Test
    void leRetraitEnregistreUneSortiePerimeeAvecLeCoutDesPortionsPerimees() {
        douzeOmelettesDontCinqPerimees();

        List<SortiePlatResponse> sorties = service.retirerPlatsPerimes(new RetraitPerimesRequest(null, null));

        assertThat(sorties).hasSize(1);
        assertThat(sorties.get(0).motif()).isEqualTo(MotifSortiePlat.PERIME);
        assertThat(sorties.get(0).quantite()).isEqualByComparingTo("5");
        ArgumentCaptor<SortiePlat> enregistree = ArgumentCaptor.forClass(SortiePlat.class);
        verify(sortiesPlats).save(enregistree.capture());
        assertThat(enregistree.getValue().getMotif()).isEqualTo(MotifSortiePlat.PERIME);
        assertThat(enregistree.getValue().getQuantite()).isEqualByComparingTo("5");
        assertThat(enregistree.getValue().getValeur()).isEqualByComparingTo("10");
        assertThat(enregistree.getValue().getPrecisionMotif())
            .contains("PRD-2026-000001").contains("Expiré");
        // Seule la part périmée sort du stock : les 7 portions bonnes restent.
        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);
        verify(stock).enregistrerSortieVenteInterne(any(), libelle.capture(), eq(entrepot), any(), any());
        assertThat(libelle.getValue()).contains("Périmé").contains("Omelette");
    }

    @Test
    void sansPlatPerimeLeRetraitEstRefuse() {
        when(niveaux.findAllWithDetails()).thenReturn(List.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("10")).valeurTotale(new BigDecimal("20")).build()));
        when(productions.findValideesDuPlatDansEntrepot(10L, 1L)).thenReturn(List.of(
            production("PRD-2026-000002", 102L, 0, 1, "10", omelette, entrepot)));
        historique(ligne(102L, StatutMouvement.VALIDE, 0, "10"));

        assertThatThrownBy(() -> service.retirerPlatsPerimes(new RetraitPerimesRequest(null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Aucun plat périmé");
        verify(sortiesPlats, never()).save(any());
    }

    @Test
    void leRetraitPeutSeLimiterAUnPlatOuAUnEntrepot() {
        douzeOmelettesDontCinqPerimees();

        assertThatThrownBy(() -> service.retirerPlatsPerimes(new RetraitPerimesRequest(999L, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.retirerPlatsPerimes(new RetraitPerimesRequest(null, 999L)))
            .isInstanceOf(IllegalStateException.class);
        verify(sortiesPlats, never()).save(any());

        assertThat(service.retirerPlatsPerimes(new RetraitPerimesRequest(10L, 1L))).hasSize(1);
    }

    @Test
    void leRetraitManuelSePasseAuNomDeLUtilisateurConnecte() {
        douzeOmelettesDontCinqPerimees();

        service.retirerPlatsPerimes(new RetraitPerimesRequest(null, null));

        ArgumentCaptor<SortiePlat> enregistree = ArgumentCaptor.forClass(SortiePlat.class);
        verify(sortiesPlats).save(enregistree.capture());
        assertThat(enregistree.getValue().getCreatedBy()).isSameAs(connecte);
        assertThat(enregistree.getValue().getPrecisionMotif()).startsWith("Expiré : ");
        verify(stock).enregistrerSortieVenteInterne(any(), anyString(), eq(entrepot), any(), eq(connecte));
    }

    // ---------------------------------------------------------------------
    // Retrait par la tâche planifiée : aucun utilisateur connecté, l'auteur est donné
    // ---------------------------------------------------------------------

    @Test
    void leRetraitInterneSePasseAuNomDeLAuteurDonneSansUtilisateurConnecte() {
        douzeOmelettesDontCinqPerimees();
        User admin = User.builder().id(1L).prenom("Marie").nom("Admin").email("admin@mbsc.cd").build();

        List<SortiePlatResponse> sorties = service.retirerPlatsPerimesInterne(admin, AUJOURDHUI, null, null, true);

        assertThat(sorties).hasSize(1);
        assertThat(sorties.get(0).quantite()).isEqualByComparingTo("5");
        ArgumentCaptor<SortiePlat> enregistree = ArgumentCaptor.forClass(SortiePlat.class);
        verify(sortiesPlats).save(enregistree.capture());
        assertThat(enregistree.getValue().getMotif()).isEqualTo(MotifSortiePlat.PERIME);
        assertThat(enregistree.getValue().getCreatedBy()).isSameAs(admin);
        assertThat(enregistree.getValue().getPrecisionMotif()).startsWith("Retrait automatique").contains("PRD-2026-000001");
        // Le mouvement de stock, et donc l'écriture comptable, est lui aussi au nom de l'administrateur.
        verify(stock).enregistrerSortieVenteInterne(any(), anyString(), eq(entrepot), any(), eq(admin));
        verify(utilisateur, never()).requireUser();
    }

    @Test
    void leRetraitInterneSansPlatPerimeRenvoieUneListeVideSansRienEnregistrer() {
        when(niveaux.findAllWithDetails()).thenReturn(List.of());

        assertThat(service.retirerPlatsPerimesInterne(connecte, AUJOURDHUI, null, null, true)).isEmpty();
        verify(sortiesPlats, never()).save(any());
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
    }

    @Test
    void leRetraitInterneJugeLaPeremptionAuJourDemande() {
        douzeOmelettesDontCinqPerimees();

        // La production n° 1 expire hier : il y a deux jours, rien n'était encore périmé.
        assertThat(service.platsPerimesInterne(AUJOURDHUI.minusDays(2))).isEmpty();
        assertThat(service.retirerPlatsPerimesInterne(connecte, AUJOURDHUI.minusDays(2), null, null, true)).isEmpty();
        assertThat(service.platsPerimesInterne(AUJOURDHUI)).hasSize(1);
    }

    @Test
    void chaqueRetraitVerrouilleLesParametresPourNePasSeCroiserAvecUnAutre() {
        douzeOmelettesDontCinqPerimees();

        service.retirerPlatsPerimes(new RetraitPerimesRequest(null, null));
        verify(parametres).findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID);

        service.retirerPlatsPerimesInterne(connecte, AUJOURDHUI, null, null, true);
        verify(parametres, org.mockito.Mockito.times(2)).findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID);
    }

    // ---------------------------------------------------------------------
    // Ne pas vendre de portions périmées
    // ---------------------------------------------------------------------

    private Vente venteDOmelettes(String quantite, LocalDate date) {
        Vente vente = Vente.builder().dateVente(date).entrepot(entrepot).build();
        vente.addLigne(LigneVente.builder().article(omelette).quantite(new BigDecimal(quantite)).build());
        return vente;
    }

    private void stockDeDouzeOmelettes() {
        when(niveaux.findByArticleAndEntrepot(omelette, entrepot)).thenReturn(Optional.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("12")).valeurTotale(new BigDecimal("24")).build()));
    }

    @Test
    void uneVenteQuiDepasseLeStockEncoreBonEstRefusee() {
        douzeOmelettesDontCinqPerimees();
        stockDeDouzeOmelettes();

        // 12 en stock dont 5 périmées : 7 se vendent, pas 8.
        assertThatThrownBy(() -> service.exigerPlatsNonPerimesInterne(venteDOmelettes("8", AUJOURDHUI)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Plat périmé")
            .hasMessageContaining("Omelette")
            .hasMessageContaining("PRD-2026-000001")
            .hasMessageContaining("Il n'en reste que 7")
            .hasMessageContaining("Sorties de plats");
    }

    @Test
    void lesPortionsEncoreBonnesSeVendentMemeQuandDAutresSontPerimees() {
        douzeOmelettesDontCinqPerimees();
        stockDeDouzeOmelettes();

        service.exigerPlatsNonPerimesInterne(venteDOmelettes("7", AUJOURDHUI));
        service.exigerPlatsNonPerimesInterne(venteDOmelettes("3", AUJOURDHUI));
    }

    @Test
    void uneVenteDateeLeJourDeLExpirationNEstPasRefusee() {
        douzeOmelettesDontCinqPerimees();
        stockDeDouzeOmelettes();

        // La production n° 1 expire hier : une vente datée d'hier se saisit encore, elle n'était pas périmée ce jour-là.
        service.exigerPlatsNonPerimesInterne(venteDOmelettes("12", AUJOURDHUI.minusDays(1)));
    }

    @Test
    void sansPortionPerimeeLaVenteNEstJamaisRefusee() {
        when(productions.findValideesDuPlatDansEntrepot(10L, 1L)).thenReturn(List.of(
            production("PRD-2026-000002", 102L, 0, 1, "10", omelette, entrepot)));
        historique(ligne(102L, StatutMouvement.VALIDE, 0, "10"));
        when(niveaux.findByArticleAndEntrepot(omelette, entrepot)).thenReturn(Optional.of(StockNiveau.builder()
            .article(omelette).entrepot(entrepot).quantite(new BigDecimal("10")).valeurTotale(new BigDecimal("20")).build()));

        service.exigerPlatsNonPerimesInterne(venteDOmelettes("10", AUJOURDHUI));
    }

    @Test
    void sansStockLaGardeDeStockInsuffisantResteLaSeuleARefuser() {
        when(niveaux.findByArticleAndEntrepot(omelette, entrepot)).thenReturn(Optional.empty());

        service.exigerPlatsNonPerimesInterne(venteDOmelettes("5", AUJOURDHUI));
        verify(productions, never()).findValideesDuPlatDansEntrepot(anyLong(), anyLong());
    }

    @Test
    void lesAutresArticlesNeSontPasConcernes() {
        Article primus = Article.builder().id(11L).code("BRAC-PRIM-55CL").libelle("Primus").type(TypeArticle.BOISSON).build();
        Vente vente = Vente.builder().dateVente(AUJOURDHUI).entrepot(entrepot).build();
        vente.addLigne(LigneVente.builder().article(primus).quantite(new BigDecimal("24")).build());

        service.exigerPlatsNonPerimesInterne(vente);
        verify(niveaux, never()).findByArticleAndEntrepot(any(), any());
        verify(sortiesPlats, never()).idsMouvementsParMotif(any());
        assertThat(new ArrayList<>(vente.getLignes())).hasSize(1);
    }
}

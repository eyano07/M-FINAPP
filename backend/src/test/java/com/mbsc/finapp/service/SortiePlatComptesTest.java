package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.SortiePlat;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.dto.restaurant.SortiePlatRequest;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.SortiePlatRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.DocumentationCompteService.RoleCompteArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sorties de plats hors vente (V121) : chaque motif est débité à son propre compte — 736.1 à 736.5 pour un plat périmé, moisi,
 * renversé, brûlé ou « autre » (variation des stocks de produits finis, à part de la sortie des plats vendus), 6276.2 pour un
 * plat offert, 6617.1 pour un repas du personnel — le compte de stock crédité restant celui du plat.
 */
class SortiePlatComptesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final String MIGRATION = "/db/migration/V121__comptes_sorties_de_plats.sql";

    /** Le compte attendu pour chaque motif : l'énoncé de la règle, écrit en dur pour qu'un changement involontaire échoue. */
    private static final Map<MotifSortiePlat, String> COMPTES = new EnumMap<>(Map.of(
        MotifSortiePlat.CADEAU, "6276.2",
        MotifSortiePlat.REPAS_PERSONNEL, "6617.1",
        MotifSortiePlat.PERIME, "736.1",
        MotifSortiePlat.MOISI, "736.2",
        MotifSortiePlat.RENVERSE, "736.3",
        MotifSortiePlat.BRULE, "736.4",
        MotifSortiePlat.AUTRE, "736.5"));

    private RestaurantService service;
    private StockService stock;
    private CompteOHADARepository comptes;

    private final Entrepot entrepot = Entrepot.builder().id(1L).code("ENT-01").nom("Entrepôt Principal").build();
    private final Article omelette = Article.builder().id(10L).code("PLAT-OMEL").libelle("Omelette").uniteMesure("portion")
        .type(TypeArticle.PLAT)
        .compteCharge(CompteOHADA.builder().numero("7361").build())
        .compteStock(CompteOHADA.builder().numero("3611").build()).build();

    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return type.cast(Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow());
    }

    @BeforeEach
    void preparer() throws Exception {
        // RestaurantService a une trentaine de dépendances : chacune est simulée, seules celles du test sont réglées.
        Constructor<?> constructeur = RestaurantService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (RestaurantService) constructeur.newInstance(arguments);
        stock = trouver(arguments, StockService.class);
        comptes = trouver(arguments, CompteOHADARepository.class);
        ArticleRepository articles = trouver(arguments, ArticleRepository.class);
        EntrepotRepository entrepots = trouver(arguments, EntrepotRepository.class);
        SortiePlatRepository sorties = trouver(arguments, SortiePlatRepository.class);
        CurrentUserProvider utilisateur = trouver(arguments, CurrentUserProvider.class);

        when(utilisateur.requireUser()).thenReturn(User.builder().id(5L).prenom("Papy").nom("Penze").email("rest@mbsc.cd").build());
        when(articles.findById(10L)).thenReturn(Optional.of(omelette));
        when(entrepots.findById(1L)).thenReturn(Optional.of(entrepot));
        // Tous les comptes des motifs existent au plan comptable.
        when(comptes.findByNumero(anyString())).thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(i.getArgument(0)).build()));
        when(sorties.save(any(SortiePlat.class))).thenAnswer(i -> {
            SortiePlat s = i.getArgument(0);
            s.setId(99L);
            return s;
        });
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            MouvementStock m = MouvementStock.builder().reference("MS-2026-000011").build();
            m.addLigne(LigneMouvementStock.builder().article(omelette).quantite(BigDecimal.ONE).montant(new BigDecimal("1.75")).build());
            return m;
        });
    }

    private void sortie(MotifSortiePlat motif) {
        service.enregistrerSortiePlat(new SortiePlatRequest(10L, BigDecimal.ONE, 1L, motif, "Cadeaux DG", JOUR));
    }

    @SuppressWarnings("unchecked")
    private StockService.SortieVente sortieDemandee() {
        ArgumentCaptor<List<StockService.SortieVente>> sorties = ArgumentCaptor.forClass(List.class);
        verify(stock).enregistrerSortieVenteInterne(eq(JOUR), anyString(), eq(entrepot), sorties.capture(), any());
        assertThat(sorties.getValue()).hasSize(1);
        StockService.SortieVente demande = sorties.getValue().get(0);
        assertThat(demande.article()).isSameAs(omelette);
        assertThat(demande.quantite()).isEqualByComparingTo("1");
        assertThat(demande.coutImpose()).isNull();
        return demande;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Le compte de chaque motif
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void chaqueMotifADesonProprecompteEtEnADesFicheDesCodes() {
        assertThat(COMPTES.keySet()).containsExactlyInAnyOrder(MotifSortiePlat.values());
        for (MotifSortiePlat motif : MotifSortiePlat.values()) {
            assertThat(RestaurantService.compteSortiePlat(motif).numero()).as(motif.name()).isEqualTo(COMPTES.get(motif));
        }
    }

    @Test
    void lesComptesDesMotifsSontTousDistinctsEtNeSontPasLeCompteDeVariationDuPlat() {
        Set<String> numeros = new HashSet<>();
        for (MotifSortiePlat motif : MotifSortiePlat.values()) {
            String numero = RestaurantService.compteSortiePlat(motif).numero();
            assertThat(numeros.add(numero)).as("compte partagé : " + numero).isTrue();
            assertThat(numero).isNotEqualTo(omelette.getCompteCharge().getNumero());
        }
        // 736.1 à 736.5 : jamais un numéro que genererCompteDedie (736 + 1 à 99) attribuerait à un plat.
        assertThat(numeros).noneMatch(n -> n.matches("736\\d+"));
    }

    @Test
    void chaqueSortieDePlatEstDebiteeAuCompteDeSonMotif() {
        for (MotifSortiePlat motif : MotifSortiePlat.values()) {
            clearInvocations(stock);

            sortie(motif);

            CompteOHADA impose = sortieDemandee().compteCharge();
            assertThat(impose).as(motif.name()).isNotNull();
            assertThat(impose.getNumero()).as(motif.name()).isEqualTo(COMPTES.get(motif));
        }
    }

    @Test
    void laSortieNeToucheNiAuCompteDeStockNiAuCoutDuPlat() {
        sortie(MotifSortiePlat.CADEAU);

        StockService.SortieVente demande = sortieDemandee();
        assertThat(demande.coutImpose()).isNull();   // coût de production moyen, comme pour une vente
        assertThat(demande.article().getCompteStock().getNumero()).isEqualTo("3611");
    }

    @Test
    void sansLeCompteDeSonMotifAuPlanComptableLaSortieEstRefuseeEtRienNEstEnregistre() {
        for (MotifSortiePlat motif : MotifSortiePlat.values()) {
            RestaurantService.CompteSortiePlat compte = RestaurantService.compteSortiePlat(motif);
            when(comptes.findByNumero(compte.numero())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sortie(motif)).as(motif.name())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(compte.numero())
                .hasMessageContaining(compte.libelle())
                .hasMessageContaining("Plan comptable")
                .hasMessageContaining("sous " + compte.racine())
                .hasMessageContaining(compte.operation());
            when(comptes.findByNumero(compte.numero())).thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(compte.numero()).build()));
        }
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
    }

    @Test
    void lesAutresMotifsNeDependentPasDuCompteDUnMotifAbsent() {
        when(comptes.findByNumero("6276.2")).thenReturn(Optional.empty());

        sortie(MotifSortiePlat.RENVERSE);

        assertThat(sortieDemandee().compteCharge().getNumero()).isEqualTo("736.3");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // La migration V121 et les fiches
    // ------------------------------------------------------------------------------------------------------------------

    private static String migration() throws IOException {
        try (InputStream in = SortiePlatComptesTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration V121").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Le texte SQL sans ses concaténations (' || ') ni ses apostrophes doublées, pour le comparer au texte Java. */
    private static String migrationEnClair() throws IOException {
        return migration().replaceAll("'\\s*\\|\\|\\s*'", "").replace("''", "'");
    }

    @Test
    void laMigrationCreeLeCompteDeChaqueMotifSousLaBonneRacineSansEcraserUnCompteExistant() throws IOException {
        String sql = migration();
        for (MotifSortiePlat motif : MotifSortiePlat.values()) {
            RestaurantService.CompteSortiePlat c = RestaurantService.compteSortiePlat(motif);
            assertThat(sql).as(motif.name())
                .contains("('" + c.racine() + "', '" + c.numero() + "', '" + c.libelle().replace("'", "''") + "',");
        }
        assertThat(sql)
            .contains("JOIN comptes_ohada p ON p.numero = v.parent")
            .contains("WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero)");
        // une seule ligne par compte : les sept numéros de la migration sont ceux du code, ni plus ni moins
        assertThat(sql.split("\\) AS v\\(parent")[0].split("\n    \\('")).hasSize(MotifSortiePlat.values().length + 1);
    }

    @Test
    void leCompteDesRepasDuPersonnelEstUneChargeDePersonnelEtLesPertesRestentEnVariationDesStocks() {
        assertThat(RestaurantService.compteSortiePlat(MotifSortiePlat.REPAS_PERSONNEL).racine()).isEqualTo("6617");
        assertThat(RestaurantService.compteSortiePlat(MotifSortiePlat.CADEAU).racine()).isEqualTo("6276");
        for (MotifSortiePlat perte : List.of(MotifSortiePlat.PERIME, MotifSortiePlat.MOISI, MotifSortiePlat.RENVERSE,
                MotifSortiePlat.BRULE, MotifSortiePlat.AUTRE)) {
            assertThat(RestaurantService.compteSortiePlat(perte).racine()).as(perte.name()).isEqualTo("736");
        }
    }

    /**
     * Les fiches des plats déjà créés reçoivent de la migration les phrases que l'application rédige désormais pour un
     * nouveau plat : les deux textes doivent rester identiques.
     */
    @Test
    void laFicheDUnNouveauPlatDitLaMemeChoseQueLaMigration() throws IOException {
        CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
        when(utilisateur.requireUser()).thenReturn(User.builder().id(1L).prenom("Marie").nom("Admin").build());
        DocumentationCompteService documentation = new DocumentationCompteService(mock(CompteOHADARepository.class), utilisateur);

        CompteOHADA racineVariation = CompteOHADA.builder().numero("736").libelle("Variations des stocks de produits finis").build();
        CompteOHADA variation = CompteOHADA.builder().numero("7362").libelle("Variations des stocks de produits finis : Riz").parent(racineVariation).build();
        documentation.documenterCompteArticle(variation, racineVariation, RoleCompteArticle.VARIATION, TypeArticle.PLAT, "PLAT-RIZ", "Riz");
        CompteOHADA racineStock = CompteOHADA.builder().numero("361").libelle("Produits finis").build();
        CompteOHADA stockPlat = CompteOHADA.builder().numero("3612").libelle("Produits finis : Riz").parent(racineStock).build();
        documentation.documenterCompteArticle(stockPlat, racineStock, RoleCompteArticle.STOCK, TypeArticle.PLAT, "PLAT-RIZ", "Riz");

        String phraseVariation = "Débité à chaque vente, au coût de production moyen. Les autres sorties de plats (périmé, moisi, "
            + "renversé, brûlé, offert, repas du personnel, autre) vont à leur propre compte (736.1 à 736.5, 6276.2 et 6617.1), pas ici.";
        String phraseStock = "Crédité à chaque sortie (vente, perte, plat offert, repas du personnel) au coût de production moyen.";
        assertThat(variation.getFonctionnement()).contains(phraseVariation).doesNotContain("repas offert");
        assertThat(stockPlat.getFonctionnement()).contains(phraseStock).doesNotContain("repas offert");
        assertThat(migrationEnClair())
            .contains(phraseVariation)
            .contains(phraseStock)
            // les anciennes phrases, rédigées avant V121, sont celles que la migration remplace
            .contains("Débité à chaque sortie (vente, perte, repas offert) au coût de production moyen.")
            .contains("Crédité à chaque sortie (vente, perte, repas offert) au coût de production moyen.");
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.DocumentationCompteService.RoleCompteArticle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comptes des boissons perdues (V120) : une boisson offerte va en 6276.1 « Boissons offertes à la clientèle », une boisson
 * périmée en 6031.2 « Boissons périmées » ; la casse garde le compte de variation de la boisson. Le 6031.1, créé par V67
 * pour les minerais sur toutes les bases, n'est jamais réutilisé.
 */
class ComptesBoissonsPerduesTest {

    private static final String MIGRATION = "/db/migration/V120__comptes_boissons_offertes_perimees.sql";

    private static String migration() throws IOException {
        try (InputStream in = ComptesBoissonsPerduesTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration V120").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Le texte SQL sans ses concaténations (' || ') ni ses apostrophes doublées, pour le comparer au texte Java. */
    private static String migrationEnClair() throws IOException {
        return migration().replaceAll("'\\s*\\|\\|\\s*'", "").replace("''", "'");
    }

    @Test
    void uneBoissonOfferteEstImputeeAuxCadeauxALaClientele() {
        assertThat(RestaurantService.compteChargePerte(TypeMouvementEmballage.CADEAU)).isEqualTo("6276.1");
    }

    @Test
    void uneBoissonPerimeeEstImputeeASonSousCompteDeVariationDesStocks() {
        assertThat(RestaurantService.compteChargePerte(TypeMouvementEmballage.PERIME)).isEqualTo("6031.2");
    }

    @Test
    void laCasseEtLesAutresMouvementsGardentLeCompteDeLaBoisson() {
        for (TypeMouvementEmballage type : TypeMouvementEmballage.values()) {
            if (type != TypeMouvementEmballage.CADEAU && type != TypeMouvementEmballage.PERIME) {
                assertThat(RestaurantService.compteChargePerte(type)).as(type.name()).isNull();
            }
        }
    }

    @Test
    void leCompteDesMineraisNEstJamaisReutilise() throws IOException {
        assertThat(List.of(RestaurantService.COMPTE_BOISSONS_OFFERTES, RestaurantService.COMPTE_BOISSONS_PERIMEES))
            .doesNotContain("6031.1");
        assertThat(migration()).doesNotContainPattern("\\('\\d+', '6031\\.1'");
    }

    @Test
    void laMigrationCreeLesDeuxComptesSousLeurParentSansEcraserUnCompteExistant() throws IOException {
        assertThat(migration())
            .contains("('6276', '" + RestaurantService.COMPTE_BOISSONS_OFFERTES + "', 'Boissons offertes à la clientèle',")
            .contains("('6031', '" + RestaurantService.COMPTE_BOISSONS_PERIMEES + "', 'Boissons périmées',")
            .contains("JOIN comptes_ohada p ON p.numero = v.parent")
            .contains("WHERE NOT EXISTS (SELECT 1 FROM comptes_ohada c WHERE c.numero = v.numero)");
    }

    /**
     * Les comptes de variation des boissons déjà ouvertes reçoivent de la migration la phrase que l'application rédige
     * désormais pour une nouvelle boisson : les deux textes doivent rester identiques.
     */
    @Test
    void laFicheDUneNouvelleBoissonDitLaMemeChoseQueLaMigration() throws IOException {
        CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
        when(utilisateur.requireUser()).thenReturn(User.builder().id(1L).prenom("Marie").nom("Admin").build());
        DocumentationCompteService documentation = new DocumentationCompteService(mock(CompteOHADARepository.class), utilisateur);
        CompteOHADA racine = CompteOHADA.builder().numero("6031").libelle("Variations des stocks de marchandises").build();
        CompteOHADA variation = CompteOHADA.builder().numero("60314").libelle("Variations des stocks de marchandises : Fanta")
            .parent(racine).build();

        documentation.documenterCompteArticle(variation, racine, RoleCompteArticle.VARIATION, TypeArticle.BOISSON, "BRAL-FANTA", "Fanta");

        String fonctionnement = "Débité à chaque vente et à chaque casse d'une bouteille pleine, au coût moyen pondéré. "
            + "Une boisson périmée est débitée en 6031.2, une boisson offerte en 6276.1.";
        String contenu = "il forme le coût d'achat des bouteilles vendues ou cassées sur la période "
            + "(les boissons périmées vont en 6031.2, les boissons offertes en 6276.1).";
        assertThat(variation.getFonctionnement()).contains(fonctionnement).doesNotContain("péremption, cadeau");
        assertThat(variation.getContenu()).contains(contenu).doesNotContain("vendues ou perdues");
        assertThat(migrationEnClair())
            .contains(fonctionnement)
            .contains(contenu)
            // les anciennes phrases, rédigées avant V120, sont celles que la migration remplace
            .contains("Débité à chaque sortie (vente, casse, péremption, cadeau) au coût moyen pondéré.")
            .contains("il forme le coût d'achat des bouteilles vendues ou perdues sur la période.");
    }
}

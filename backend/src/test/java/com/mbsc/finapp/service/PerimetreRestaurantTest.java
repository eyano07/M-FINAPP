package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.service.PerimetreRestaurant.Perimetre;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que chaque role du restaurant a le droit de voir : cuisine (plats, provisions), bar (boissons), ou tout.
 * La regle porte sur les types d'articles, elle est appliquee cote serveur par RestaurantService et StockService.
 */
class PerimetreRestaurantTest {

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private static Set<String> roles(String... noms) {
        Set<String> autorites = new java.util.HashSet<>();
        for (String nom : noms) {
            autorites.add("ROLE_" + nom);
        }
        return autorites;
    }

    private static Set<TypeArticle> vus(Perimetre p) {
        Set<TypeArticle> visibles = EnumSet.noneOf(TypeArticle.class);
        for (TypeArticle t : TypeArticle.values()) {
            if (p.autorise(t)) {
                visibles.add(t);
            }
        }
        return visibles;
    }

    // ---------------------------------------------------------------------
    // Les deux roles de domaine, seuls
    // ---------------------------------------------------------------------

    @Test
    void le_responsable_cuisine_seul_ne_voit_que_les_plats_et_les_provisions() {
        for (Perimetre p : List.of(PerimetreRestaurant.restaurant(roles("RESP_CUISINE")),
                                   PerimetreRestaurant.logistique(roles("RESP_CUISINE")))) {
            assertThat(p.restreint()).isTrue();
            assertThat(vus(p)).containsExactlyInAnyOrder(TypeArticle.PLAT, TypeArticle.PROVISION);
        }
    }

    @Test
    void le_responsable_bar_seul_ne_voit_que_les_boissons() {
        for (Perimetre p : List.of(PerimetreRestaurant.restaurant(roles("RESP_BAR")),
                                   PerimetreRestaurant.logistique(roles("RESP_BAR")))) {
            assertThat(p.restreint()).isTrue();
            assertThat(vus(p)).containsExactly(TypeArticle.BOISSON);
        }
    }

    @Test
    void cuisine_et_bar_cumules_voient_les_deux_domaines_mais_pas_le_reste_du_catalogue() {
        Perimetre p = PerimetreRestaurant.logistique(roles("RESP_CUISINE", "RESP_BAR"));

        assertThat(p.restreint()).isTrue();
        assertThat(vus(p)).containsExactlyInAnyOrder(TypeArticle.PLAT, TypeArticle.PROVISION, TypeArticle.BOISSON);
        assertThat(p.autorise(TypeArticle.MARCHANDISE)).isFalse();
        assertThat(p.autorise(TypeArticle.CONSOMMABLE)).isFalse();
        assertThat(p.autorise(TypeArticle.SERVICE)).isFalse();
    }

    // ---------------------------------------------------------------------
    // Le responsable restaurant : tout dans le restaurant, trois types seulement dans la logistique
    // ---------------------------------------------------------------------

    @Test
    void le_responsable_restaurant_voit_tout_le_restaurant() {
        assertThat(PerimetreRestaurant.restaurant(roles("RESP_RESTAURANT")).restreint()).isFalse();
    }

    @Test
    void dans_la_logistique_le_responsable_restaurant_compte_comme_un_role_de_domaine_a_trois_types() {
        Perimetre p = PerimetreRestaurant.logistique(roles("RESP_RESTAURANT"));

        assertThat(p.restreint()).isTrue();
        assertThat(vus(p)).containsExactlyInAnyOrder(TypeArticle.PLAT, TypeArticle.PROVISION, TypeArticle.BOISSON);
        // Cumule avec le bar, il garde ses trois types.
        assertThat(vus(PerimetreRestaurant.logistique(roles("RESP_RESTAURANT", "RESP_BAR"))))
            .containsExactlyInAnyOrder(TypeArticle.PLAT, TypeArticle.PROVISION, TypeArticle.BOISSON);
    }

    // ---------------------------------------------------------------------
    // Un role « complet » leve la restriction (les roles s'additionnent)
    // ---------------------------------------------------------------------

    @Test
    void un_role_complet_du_restaurant_leve_la_restriction() {
        for (String complet : List.of("DFIN", "DG", "DA", "COMPTABLE", "CAISSIER", "ADMIN", "RESP_RESTAURANT")) {
            assertThat(PerimetreRestaurant.restaurant(roles("RESP_BAR", complet)).restreint())
                .as("RESP_BAR + " + complet).isFalse();
            assertThat(PerimetreRestaurant.restaurant(roles("RESP_CUISINE", complet)).restreint())
                .as("RESP_CUISINE + " + complet).isFalse();
        }
    }

    @Test
    void un_role_sans_rapport_avec_le_restaurant_ne_leve_pas_la_restriction() {
        for (String autre : List.of("RESP_DRH", "GEST_PATRIMOINE", "LOGISTIQUE", "DIRECTEUR")) {
            assertThat(PerimetreRestaurant.restaurant(roles("RESP_BAR", autre)).restreint())
                .as("RESP_BAR + " + autre).isTrue();
        }
    }

    @Test
    void dans_la_logistique_les_roles_complets_sont_ceux_de_la_garde_de_lecture_du_stock() {
        for (String complet : List.of("LOGISTIQUE", "CAISSIER", "COMPTABLE", "GEST_PATRIMOINE", "DFIN", "DA", "DG", "ADMIN")) {
            assertThat(PerimetreRestaurant.logistique(roles("RESP_CUISINE", complet)).restreint())
                .as("RESP_CUISINE + " + complet).isFalse();
        }
        // RESP_DRH n'a aucun droit logistique : il ne change rien.
        assertThat(PerimetreRestaurant.logistique(roles("RESP_BAR", "RESP_DRH")).restreint()).isTrue();
        // LOGISTIQUE n'est pas un role complet du restaurant : le responsable cuisine y reste restreint.
        assertThat(PerimetreRestaurant.restaurant(roles("RESP_CUISINE", "LOGISTIQUE")).restreint()).isTrue();
    }

    @Test
    void sans_role_du_restaurant_ou_sans_contexte_il_n_y_a_pas_de_restriction() {
        assertThat(PerimetreRestaurant.restaurant(roles("DFIN")).restreint()).isFalse();
        assertThat(PerimetreRestaurant.restaurant(roles()).restreint()).isFalse();
        assertThat(PerimetreRestaurant.restaurant(null).restreint()).isFalse();
        assertThat(PerimetreRestaurant.logistique(null).restreint()).isFalse();
        assertThat(Perimetre.SANS_RESTRICTION.autorise(TypeArticle.MARCHANDISE)).isTrue();
        assertThat(Perimetre.SANS_RESTRICTION.autorise(null)).isTrue();
    }

    @Test
    void un_perimetre_restreint_refuse_un_type_inconnu() {
        assertThat(PerimetreRestaurant.restaurant(roles("RESP_BAR")).autorise(null)).isFalse();
    }

    // ---------------------------------------------------------------------
    // Filtrage d'une liste
    // ---------------------------------------------------------------------

    private record Ligne(String nom, TypeArticle type) {
    }

    @Test
    void filtrer_ne_garde_que_les_elements_du_domaine_et_laisse_tout_passer_sans_restriction() {
        List<Ligne> lignes = List.of(
            new Ligne("Poulet braise", TypeArticle.PLAT),
            new Ligne("Coca 33 cl", TypeArticle.BOISSON),
            new Ligne("Farine", TypeArticle.PROVISION),
            new Ligne("Chaise", TypeArticle.MARCHANDISE));

        assertThat(PerimetreRestaurant.restaurant(roles("RESP_CUISINE")).filtrer(lignes, Ligne::type))
            .extracting(Ligne::nom).containsExactly("Poulet braise", "Farine");
        assertThat(PerimetreRestaurant.restaurant(roles("RESP_BAR")).filtrer(lignes, Ligne::type))
            .extracting(Ligne::nom).containsExactly("Coca 33 cl");
        assertThat(PerimetreRestaurant.restaurant(roles("DFIN")).filtrer(lignes, Ligne::type)).hasSize(4);
    }

    @Test
    void cuisine_plus_bar_egale_le_perimetre_du_responsable_restaurant_sur_les_types_du_restaurant() {
        List<Ligne> lignes = List.of(
            new Ligne("Poulet braise", TypeArticle.PLAT),
            new Ligne("Coca 33 cl", TypeArticle.BOISSON),
            new Ligne("Farine", TypeArticle.PROVISION));

        List<Ligne> cuisine = PerimetreRestaurant.restaurant(roles("RESP_CUISINE")).filtrer(lignes, Ligne::type);
        List<Ligne> bar = PerimetreRestaurant.restaurant(roles("RESP_BAR")).filtrer(lignes, Ligne::type);
        List<Ligne> global = PerimetreRestaurant.restaurant(roles("RESP_RESTAURANT")).filtrer(lignes, Ligne::type);

        assertThat(cuisine).doesNotContainAnyElementsOf(bar);
        assertThat(java.util.stream.Stream.concat(cuisine.stream(), bar.stream()).toList())
            .containsExactlyInAnyOrderElementsOf(global);
    }

    // ---------------------------------------------------------------------
    // Types qu'une note de frais d'achat peut porter
    // ---------------------------------------------------------------------

    @Test
    void types_achetables_par_role() {
        assertThat(PerimetreRestaurant.typesAchetables(roles("RESP_CUISINE"))).containsExactly(TypeArticle.PROVISION);
        assertThat(PerimetreRestaurant.typesAchetables(roles("RESP_BAR"))).containsExactly(TypeArticle.BOISSON);
        assertThat(PerimetreRestaurant.typesAchetables(roles("RESP_RESTAURANT")))
            .containsExactlyInAnyOrder(TypeArticle.BOISSON, TypeArticle.PROVISION);
        assertThat(PerimetreRestaurant.typesAchetables(roles("RESP_CUISINE", "RESP_BAR")))
            .containsExactlyInAnyOrder(TypeArticle.BOISSON, TypeArticle.PROVISION);
        // Un plat ne s'achete jamais : il se produit.
        assertThat(PerimetreRestaurant.typesAchetables(roles("RESP_RESTAURANT", "RESP_CUISINE", "RESP_BAR")))
            .doesNotContain(TypeArticle.PLAT);
        assertThat(PerimetreRestaurant.typesAchetables(roles("DFIN"))).isEmpty();
        assertThat(PerimetreRestaurant.typesAchetables(null)).isEmpty();
    }

    @Test
    void les_libelles_des_achats_suivent_les_types_autorises() {
        Set<TypeArticle> cuisine = PerimetreRestaurant.typesAchetables(roles("RESP_CUISINE"));
        Set<TypeArticle> bar = PerimetreRestaurant.typesAchetables(roles("RESP_BAR"));
        Set<TypeArticle> global = PerimetreRestaurant.typesAchetables(roles("RESP_RESTAURANT"));

        assertThat(PerimetreRestaurant.libelleAchats(cuisine)).isEqualTo("provisions");
        assertThat(PerimetreRestaurant.libelleAchats(bar)).isEqualTo("boissons");
        assertThat(PerimetreRestaurant.libelleAchats(global)).isEqualTo("boissons ou de provisions");
        assertThat(PerimetreRestaurant.libelleTypes(cuisine)).isEqualTo("PROVISION");
        assertThat(PerimetreRestaurant.libelleTypes(bar)).isEqualTo("BOISSON");
        assertThat(PerimetreRestaurant.libelleTypes(global)).isEqualTo("BOISSON ou PROVISION");
    }

    // ---------------------------------------------------------------------
    // Contexte de securite courant
    // ---------------------------------------------------------------------

    @Test
    void les_autorites_courantes_viennent_du_contexte_de_securite() {
        assertThat(PerimetreRestaurant.autoritesCourantes()).isNull();
        assertThat(PerimetreRestaurant.restaurantCourant().restreint()).isFalse();

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_RESP_BAR"));

        assertThat(PerimetreRestaurant.autoritesCourantes()).containsExactly("ROLE_RESP_BAR");
        assertThat(PerimetreRestaurant.restaurantCourant().restreint()).isTrue();
        assertThat(PerimetreRestaurant.logistiqueCourant().autorise(TypeArticle.BOISSON)).isTrue();
        assertThat(PerimetreRestaurant.logistiqueCourant().autorise(TypeArticle.PLAT)).isFalse();
    }
}

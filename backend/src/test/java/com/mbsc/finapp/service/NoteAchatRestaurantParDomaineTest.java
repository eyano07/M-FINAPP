package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.TypeArticle;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'une note de frais d'un responsable du restaurant peut acheter, selon son domaine : le responsable cuisine des
 * provisions, le responsable bar des boissons, le responsable restaurant les deux — jamais un plat (il se produit)
 * ni un article hors du restaurant, ni une depense libre.
 */
class NoteAchatRestaurantParDomaineTest {

    private static Set<String> roles(String... noms) {
        Set<String> autorites = new HashSet<>();
        for (String nom : noms) {
            autorites.add("ROLE_" + nom);
        }
        return autorites;
    }

    private static boolean admise(Set<String> autorites, TypeArticle type, boolean achatMarchandise) {
        return NoteFraisService.ligneAchatAdmise(achatMarchandise, type, PerimetreRestaurant.typesAchetables(autorites));
    }

    @Test
    void le_responsable_cuisine_n_achete_que_des_provisions() {
        Set<String> cuisine = roles("RESP_CUISINE");

        assertThat(admise(cuisine, TypeArticle.PROVISION, true)).isTrue();
        assertThat(admise(cuisine, TypeArticle.BOISSON, true)).isFalse();
        assertThat(admise(cuisine, TypeArticle.PLAT, true)).isFalse();
        assertThat(admise(cuisine, TypeArticle.MARCHANDISE, true)).isFalse();
    }

    @Test
    void le_responsable_bar_n_achete_que_des_boissons() {
        Set<String> bar = roles("RESP_BAR");

        assertThat(admise(bar, TypeArticle.BOISSON, true)).isTrue();
        assertThat(admise(bar, TypeArticle.PROVISION, true)).isFalse();
        assertThat(admise(bar, TypeArticle.PLAT, true)).isFalse();
    }

    @Test
    void le_responsable_restaurant_et_le_cumul_cuisine_bar_achetent_boissons_et_provisions() {
        for (Set<String> role : java.util.List.of(roles("RESP_RESTAURANT"), roles("RESP_CUISINE", "RESP_BAR"))) {
            assertThat(admise(role, TypeArticle.BOISSON, true)).as(role.toString()).isTrue();
            assertThat(admise(role, TypeArticle.PROVISION, true)).as(role.toString()).isTrue();
            assertThat(admise(role, TypeArticle.PLAT, true)).as(role.toString()).isFalse();
            assertThat(admise(role, TypeArticle.SERVICE, true)).as(role.toString()).isFalse();
        }
    }

    @Test
    void une_depense_libre_ou_une_ligne_sans_article_est_refusee_pour_tous() {
        for (Set<String> role : java.util.List.of(roles("RESP_RESTAURANT"), roles("RESP_CUISINE"), roles("RESP_BAR"))) {
            assertThat(admise(role, TypeArticle.PROVISION, false)).as(role + " : pas un achat de marchandise").isFalse();
            assertThat(admise(role, TypeArticle.BOISSON, false)).as(role + " : pas un achat de marchandise").isFalse();
            assertThat(admise(role, null, true)).as(role + " : ligne sans article").isFalse();
        }
    }

    @Test
    void un_compte_sans_role_du_restaurant_n_a_aucun_achat_autorise() {
        assertThat(admise(roles("COMPTABLE"), TypeArticle.PROVISION, true)).isFalse();
    }
}

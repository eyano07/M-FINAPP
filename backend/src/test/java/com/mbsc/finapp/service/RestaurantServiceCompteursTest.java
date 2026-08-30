package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.EmballageBoisson;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie la seule regle metier du stock d'emballages : l'effet d'un mouvement
 * sur le compteur de bouteilles vides, et la conversion en casiers.
 *
 * <p>Les quatre premiers cas rejouent litteralement les exemples fournis par
 * le metier pour un casier de 24 bouteilles de Coca 33 cl, afin qu'ils restent
 * verifiables a la lecture sans deroule mental.</p>
 */
class RestaurantServiceCompteursTest {

    private static final int CASIER = 24;

    private static int appliquer(int avant, TypeMouvementEmballage type, int quantite) {
        return RestaurantService.appliquer(avant, type, quantite);
    }

    /** Reconstitue l'affichage « N casiers + M bouteilles » a partir du compteur. */
    private static EmballageBoisson emballage(int vides) {
        return EmballageBoisson.builder()
            .contenanceCasier(CASIER)
            .bouteillesVides(vides)
            .build();
    }

    // ── Les exemples du métier ───────────────────────────────────────────

    @Test
    void depart_3_casiers_et_8_bouteilles_font_80_vides() {
        EmballageBoisson e = emballage(3 * CASIER + 8);

        assertThat(e.getBouteillesVides()).isEqualTo(80);
        assertThat(e.casiers()).isEqualTo(3);
        assertThat(e.bouteillesRestantes()).isEqualTo(8);
    }

    @Test
    void vendre_3_bouteilles_donne_3_casiers_et_11() {
        int apres = appliquer(80, TypeMouvementEmballage.VENTE, 3);

        assertThat(apres).isEqualTo(83);
        assertThat(emballage(apres).casiers()).isEqualTo(3);
        assertThat(emballage(apres).bouteillesRestantes()).isEqualTo(11);
    }

    @Test
    void vendre_28_de_plus_donne_4_casiers_et_15() {
        int apres = appliquer(83, TypeMouvementEmballage.VENTE, 28);

        assertThat(apres).isEqualTo(111);
        assertThat(emballage(apres).casiers()).isEqualTo(4);
        assertThat(emballage(apres).bouteillesRestantes()).isEqualTo(15);
    }

    /** Acheter 2 casiers fait rendre 2 x 24 = 48 vides : 80 - 48 = 32. */
    @Test
    void acheter_2_casiers_donne_1_casier_et_8() {
        int apres = appliquer(80, TypeMouvementEmballage.ACHAT, 2 * CASIER);

        assertThat(apres).isEqualTo(32);
        assertThat(emballage(apres).casiers()).isEqualTo(1);
        assertThat(emballage(apres).bouteillesRestantes()).isEqualTo(8);
    }

    // ── Casse et ajustements ─────────────────────────────────────────────

    @Test
    void casse_d_une_bouteille_vide_retire_du_stock_de_vides() {
        assertThat(appliquer(80, TypeMouvementEmballage.CASSE, 5)).isEqualTo(75);
    }

    /**
     * Une bouteille PLEINE qui casse n'a jamais ete servie : aucune vide n'a
     * ete produite, et le contenant lui-meme est perdu. Le stock de vides
     * diminue quand meme — exactement comme une casse ordinaire — parce que
     * cette bouteille ne reviendra jamais completer le stock de vides non
     * plus. Le stock de la boisson (Article) diminue separement, teste au
     * niveau service (RestaurantService.enregistrerMouvement), pas ici.
     */
    @Test
    void casse_d_une_bouteille_pleine_retire_aussi_du_stock_de_vides() {
        assertThat(appliquer(80, TypeMouvementEmballage.CASSE_PLEINE, 5)).isEqualTo(75);
    }

    /**
     * Une boisson perimee est jetee, mais la bouteille elle-meme n'est pas
     * cassee : elle sera videe et pourra etre rendue normalement. Le stock de
     * vides ne bouge donc pas — seul le stock de la boisson diminue (teste au
     * niveau service).
     */
    @Test
    void boisson_perimee_ne_change_pas_le_stock_de_vides() {
        assertThat(appliquer(80, TypeMouvementEmballage.PERIME, 5)).isEqualTo(80);
    }

    /**
     * Boisson offerte (cadeau, promotion) : meme traitement que PERIME — le
     * contenant reste intact, seul le stock de boisson diminue (teste au
     * niveau service).
     */
    @Test
    void boisson_offerte_en_cadeau_ne_change_pas_le_stock_de_vides() {
        assertThat(appliquer(80, TypeMouvementEmballage.CADEAU, 5)).isEqualTo(80);
    }

    @Test
    void annulation_de_vente_reprend_les_vides() {
        assertThat(appliquer(83, TypeMouvementEmballage.RETOUR_VENTE, 3)).isEqualTo(80);
    }

    @Test
    void ajustements_d_inventaire_dans_les_deux_sens() {
        assertThat(appliquer(80, TypeMouvementEmballage.AJUSTEMENT_PLUS, 4)).isEqualTo(84);
        assertThat(appliquer(80, TypeMouvementEmballage.AJUSTEMENT_MOINS, 4)).isEqualTo(76);
    }

    // ── Gardes ───────────────────────────────────────────────────────────

    @Test
    void achat_au_dela_du_stock_de_vides_est_refuse() {
        assertThatThrownBy(() -> appliquer(30, TypeMouvementEmballage.ACHAT, 2 * CASIER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("insuffisantes");
    }

    @Test
    void casse_au_dela_du_stock_est_refusee() {
        assertThatThrownBy(() -> appliquer(2, TypeMouvementEmballage.CASSE, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("insuffisantes");
    }

    /** Décision produit : casser une bouteille pleine reste bloqué s'il n'y a pas assez de vides — même garde que CASSE. */
    @Test
    void casse_pleine_au_dela_du_stock_de_vides_est_refusee() {
        assertThatThrownBy(() -> appliquer(2, TypeMouvementEmballage.CASSE_PLEINE, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("insuffisantes");
    }

    @Test
    void quantite_nulle_ou_negative_est_refusee() {
        assertThatThrownBy(() -> appliquer(80, TypeMouvementEmballage.VENTE, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictement positive");

        assertThatThrownBy(() -> appliquer(80, TypeMouvementEmballage.CASSE, -5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictement positive");
    }

    /** Vider exactement le stock est legitime : la garde porte sur le passage sous zero. */
    @Test
    void consommer_exactement_tout_le_stock_est_accepte() {
        assertThat(appliquer(48, TypeMouvementEmballage.ACHAT, 48)).isZero();
    }

    // ── Conversion en casiers ────────────────────────────────────────────

    @Test
    void un_stock_inferieur_a_un_casier_n_affiche_aucun_casier() {
        EmballageBoisson e = emballage(23);

        assertThat(e.casiers()).isZero();
        assertThat(e.bouteillesRestantes()).isEqualTo(23);
    }

    @Test
    void un_stock_multiple_exact_n_affiche_aucune_bouteille_isolee() {
        EmballageBoisson e = emballage(72);

        assertThat(e.casiers()).isEqualTo(3);
        assertThat(e.bouteillesRestantes()).isZero();
    }

    // ── Plafonnement : les chemins qui ne doivent jamais échouer ─────────
    //
    // Le règlement d'une note de frais et l'annulation d'une vente portent sur
    // des faits deja acquis (fournisseur livre, vente saisie par erreur). Les
    // refuser parce que les vides ont ete consommes entre-temps bloquerait une
    // operation qu'on ne peut plus annuler autrement : on applique ce qui peut
    // l'etre. Ces cas ont bloque un paiement en production avant correction.

    @Test
    void un_echange_de_consigne_est_plafonne_au_stock_reellement_disponible() {
        assertThat(RestaurantService.plafonner(2 * CASIER, 20)).isEqualTo(20);
    }

    @Test
    void un_echange_de_consigne_sans_aucun_vide_ne_s_applique_pas_du_tout() {
        assertThat(RestaurantService.plafonner(2 * CASIER, 0)).isZero();
    }

    @Test
    void un_echange_de_consigne_couvert_par_le_stock_s_applique_en_entier() {
        assertThat(RestaurantService.plafonner(2 * CASIER, 100)).isEqualTo(2 * CASIER);
    }

    @Test
    void le_plafonnement_ne_rend_jamais_une_quantite_negative() {
        assertThat(RestaurantService.plafonner(10, -5)).isZero();
    }

    /** Le plafond n'assouplit pas la regle : le compteur reste coherent apres application. */
    @Test
    void un_achat_plafonne_ramene_le_stock_a_zero_sans_le_rendre_negatif() {
        int disponible = 20;
        int applique = RestaurantService.plafonner(2 * CASIER, disponible);

        assertThat(appliquer(disponible, TypeMouvementEmballage.ACHAT, applique)).isZero();
    }

    // ── Quantites fractionnaires ─────────────────────────────────────────
    //
    // LigneVente.quantite est un decimal alors qu'une bouteille est
    // indivisible. Tronquer ramenait une vente inferieure a une bouteille a
    // zero, quantite que appliquer() refuse : la vente entiere echouait.

    @Test
    void une_vente_de_moins_d_une_bouteille_compte_quand_meme_un_vide() {
        assertThat(RestaurantService.bouteilles(new java.math.BigDecimal("0.5"))).isEqualTo(1);
    }

    @Test
    void une_quantite_fractionnaire_est_arrondie_au_plus_proche_et_non_tronquee() {
        assertThat(RestaurantService.bouteilles(new java.math.BigDecimal("1.5"))).isEqualTo(2);
        assertThat(RestaurantService.bouteilles(new java.math.BigDecimal("2.4"))).isEqualTo(2);
    }

    @Test
    void une_quantite_entiere_reste_inchangee() {
        assertThat(RestaurantService.bouteilles(new java.math.BigDecimal("24.000"))).isEqualTo(24);
    }

    @Test
    void une_quantite_absente_ne_produit_aucun_vide() {
        assertThat(RestaurantService.bouteilles(null)).isZero();
    }
}

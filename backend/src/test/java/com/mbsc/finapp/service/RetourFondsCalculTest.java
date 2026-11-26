package com.mbsc.finapp.service;

import com.mbsc.finapp.service.RetourFondsCalcul.Demande;
import com.mbsc.finapp.service.RetourFondsCalcul.LigneNote;
import com.mbsc.finapp.service.RetourFondsCalcul.PartCredit;
import com.mbsc.finapp.service.RetourFondsCalcul.Reduction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retour de fonds sur une note payee : la reduction demandee, ce qui est rendu (TTC, devise de la note) et sa ventilation
 * en devise de base, miroir du paiement (RegleTresorerieService.construireLignesDebitDepuisNote).
 */
class RetourFondsCalculTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static LigneNote depense(long id, String montant) {
        return new LigneNote(id, bd(montant), null, false, false, false, null, false);
    }

    private static LigneNote depenseTva(long id, String montant) {
        return new LigneNote(id, bd(montant), null, false, false, true, bd("16"), true);
    }

    private static LigneNote achat(long id, String prixUnitaire, String quantite) {
        return new LigneNote(id, bd(prixUnitaire), bd(quantite), true, false, false, null, false);
    }

    private static LigneNote achatTva(long id, String prixUnitaire, String quantite) {
        return new LigneNote(id, bd(prixUnitaire), bd(quantite), true, false, true, bd("16"), true);
    }

    private static LigneNote transport(long id, String montant) {
        return new LigneNote(id, bd(montant), null, false, true, false, null, false);
    }

    private static Demande montant(long id, String nouveau) {
        return new Demande(id, null, bd(nouveau));
    }

    private static Demande quantite(long id, String nouvelle) {
        return new Demande(id, bd(nouvelle), null);
    }

    // ---------------------------------------------------------------------
    // Ce qui est rendu
    // ---------------------------------------------------------------------

    @Test
    void reduire_le_montant_d_une_depense_rend_la_difference() {
        List<Reduction> r = RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(montant(1, "70")));
        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.ancienMontant()).isEqualByComparingTo("100.00");
            assertThat(x.nouveauMontant()).isEqualByComparingTo("70.00");
            assertThat(x.ancienneQuantite()).isNull();
            assertThat(x.montantRetour()).isEqualByComparingTo("30.00");
            assertThat(x.quantiteRendue()).isEqualByComparingTo("0");
        });
    }

    @Test
    void une_depense_soumise_a_la_tva_rend_la_difference_ttc() {
        // 100 HT + 16 % = 116 TTC ; 50 HT + 16 % = 58 TTC : 58 rendus
        Reduction r = RetourFondsCalcul.reductions(List.of(depenseTva(1, "100.00")), List.of(montant(1, "50"))).get(0);
        assertThat(r.montantRetour()).isEqualByComparingTo("58.00");
    }

    @Test
    void un_achat_se_reduit_en_quantite_au_meme_prix_unitaire() {
        // 24 bouteilles a 2,50 = 60,00 ; 12 bouteilles = 30,00 : 30,00 rendus, 12 bouteilles rendues
        Reduction r = RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")), List.of(quantite(1, "12"))).get(0);
        assertThat(r.nouveauMontant()).isEqualByComparingTo("2.50");
        assertThat(r.ancienneQuantite()).isEqualByComparingTo("24");
        assertThat(r.nouvelleQuantite()).isEqualByComparingTo("12");
        assertThat(r.quantiteRendue()).isEqualByComparingTo("12");
        assertThat(r.montantRetour()).isEqualByComparingTo("30.00");
    }

    @Test
    void un_achat_soumis_a_la_tva_rend_la_difference_ttc() {
        // 24 x 2,50 = 60,00 HT -> 69,60 TTC ; 18 x 2,50 = 45,00 HT -> 52,20 TTC : 17,40 rendus
        Reduction r = RetourFondsCalcul.reductions(List.of(achatTva(1, "2.50", "24")), List.of(quantite(1, "18"))).get(0);
        assertThat(r.montantRetour()).isEqualByComparingTo("17.40");
    }

    @Test
    void une_provision_au_kilo_se_reduit_d_une_quantite_fractionnaire() {
        // 12,5 kg a 3,20 = 40,00 ; 10,25 kg = 32,80 : 7,20 rendus, 2,25 kg rendus
        Reduction r = RetourFondsCalcul.reductions(List.of(achat(1, "3.20", "12.5")), List.of(quantite(1, "10.25"))).get(0);
        assertThat(r.montantRetour()).isEqualByComparingTo("7.20");
        assertThat(r.quantiteRendue()).isEqualByComparingTo("2.25");
    }

    @Test
    void une_ligne_peut_etre_ramenee_a_zero() {
        Reduction r = RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")), List.of(quantite(1, "0"))).get(0);
        assertThat(r.montantRetour()).isEqualByComparingTo("60.00");
        assertThat(r.quantiteRendue()).isEqualByComparingTo("24");
    }

    @Test
    void plusieurs_lignes_et_total() {
        List<Reduction> r = RetourFondsCalcul.reductions(
            List.of(depense(1, "100.00"), achat(2, "2.50", "24"), depense(3, "15.00")),
            List.of(montant(1, "80"), quantite(2, "20")));
        assertThat(r).extracting(Reduction::ligneId).containsExactly(1L, 2L);
        assertThat(RetourFondsCalcul.total(r)).isEqualByComparingTo("30.00");   // 20 + 10
    }

    // ---------------------------------------------------------------------
    // Ce qui est refuse
    // ---------------------------------------------------------------------

    @Test
    void on_ne_peut_que_reduire() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(montant(1, "120"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("On ne peut que réduire");
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")), List.of(quantite(1, "30"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("On ne peut que réduire");
    }

    @Test
    void ni_montant_ni_quantite_negatifs() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(montant(1, "-1"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("négatif");
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")), List.of(quantite(1, "-2"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("négative");
    }

    @Test
    void un_achat_se_reduit_en_quantite_jamais_en_prix() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")),
            List.of(new Demande(1L, bd("24"), bd("2.00")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pas le prix unitaire");
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(achat(1, "2.50", "24")), List.of(montant(1, "2.50"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nouvelle quantité");
    }

    @Test
    void une_depense_se_reduit_en_montant_jamais_en_quantite() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(quantite(1, "1"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("réduisez son montant");
    }

    @Test
    void les_frais_d_approche_ne_se_reduisent_pas() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(transport(1, "20.00")), List.of(montant(1, "10"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frais d'approche");
        // cites sans changement avec une vraie reduction ailleurs : simplement ignores
        List<Reduction> r = RetourFondsCalcul.reductions(List.of(transport(1, "20.00"), depense(2, "50.00")),
            List.of(montant(1, "20.00"), montant(2, "40")));
        assertThat(r).extracting(Reduction::ligneId).containsExactly(2L);
    }

    @Test
    void ligne_inconnue_ou_citee_deux_fois() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(montant(9, "10"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ligne inconnue");
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")),
            List.of(montant(1, "90"), montant(1, "80"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deux fois");
    }

    @Test
    void sans_reduction_reelle_la_demande_est_refusee() {
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of(montant(1, "100"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Aucune ligne n'est réduite");
        assertThatThrownBy(() -> RetourFondsCalcul.reductions(List.of(depense(1, "100.00")), List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("au moins une ligne");
    }

    // ---------------------------------------------------------------------
    // Ventilation en devise de base (miroir du paiement)
    // ---------------------------------------------------------------------

    private static Map<Long, LigneNote> parId(LigneNote... lignes) {
        Map<Long, LigneNote> m = new LinkedHashMap<>();
        for (LigneNote l : lignes) {
            m.put(l.id(), l);
        }
        return m;
    }

    @Test
    void ventilation_en_dollars_la_tva_retourne_a_son_compte() {
        LigneNote l1 = depenseTva(1, "100.00");
        LigneNote l2 = depense(2, "50.00");
        List<Reduction> r = RetourFondsCalcul.reductions(List.of(l1, l2), List.of(montant(1, "50"), montant(2, "20")));
        // 58,00 TTC rendus sur la ligne 1 (50 HT + 8 TVA), 30,00 sur la ligne 2
        List<PartCredit> parts = RetourFondsCalcul.ventiler(r, parId(l1, l2), RetourFondsCalcul.total(r), Function.identity());
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).ht()).isEqualByComparingTo("50.00");
        assertThat(parts.get(0).tva()).isEqualByComparingTo("8.00");
        assertThat(parts.get(1).ht()).isEqualByComparingTo("30.00");
        assertThat(parts.get(1).tva()).isEqualByComparingTo("0");
        assertThat(parts.stream().map(p -> p.ht().add(p.tva())).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("88.00");
    }

    @Test
    void ventilation_en_francs_au_taux_du_paiement_la_derniere_ligne_absorbe_l_arrondi() {
        BigDecimal taux = bd("2800");
        Function<BigDecimal, BigDecimal> versBase = m -> m.divide(taux, 2, RoundingMode.HALF_UP);
        LigneNote l1 = depense(1, "30000");
        LigneNote l2 = depense(2, "20000");
        List<Reduction> r = RetourFondsCalcul.reductions(List.of(l1, l2), List.of(montant(1, "20000"), montant(2, "15000")));
        BigDecimal totalBase = versBase.apply(RetourFondsCalcul.total(r));          // 15 000 FC -> 5,36 USD
        List<PartCredit> parts = RetourFondsCalcul.ventiler(r, parId(l1, l2), totalBase, versBase);
        assertThat(parts.get(0).ht()).isEqualByComparingTo("3.57");                  // 10 000 FC
        assertThat(parts.get(1).ht()).isEqualByComparingTo("1.79");                  // 5,36 - 3,57 (et non 5 000 / 2 800 = 1,79 par hasard)
        assertThat(parts.get(0).ht().add(parts.get(1).ht())).isEqualByComparingTo(totalBase);
    }

    @Test
    void sans_compte_de_tva_tout_le_retour_va_au_compte_de_la_ligne_comme_au_paiement() {
        LigneNote l = new LigneNote(1L, bd("100.00"), null, false, false, true, bd("16"), false);
        List<Reduction> r = RetourFondsCalcul.reductions(List.of(l), List.of(montant(1, "50")));
        List<PartCredit> parts = RetourFondsCalcul.ventiler(r, parId(l), RetourFondsCalcul.total(r), Function.identity());
        assertThat(parts.get(0).ht()).isEqualByComparingTo("58.00");
        assertThat(parts.get(0).tva()).isEqualByComparingTo("0");
    }

    @Test
    void le_ttc_suit_la_regle_de_la_ligne_de_note() {
        assertThat(RetourFondsCalcul.ttc(bd("2.50"), bd("24"), true, true, bd("16"))).isEqualByComparingTo("69.60");
        assertThat(RetourFondsCalcul.ttc(bd("100.00"), null, false, false, null)).isEqualByComparingTo("100.00");
        assertThat(RetourFondsCalcul.ttc(bd("100.00"), bd("3"), false, false, null)).isEqualByComparingTo("100.00");
    }
}

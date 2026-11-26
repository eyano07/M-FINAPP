package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calculs budgetaires : ventilation au centime pres, periodes coherentes, rattachement des comptes SYSCOHADA,
 * sens du realise et controle du disponible.
 */
class MoteurBudgetaireTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal[] mois(String... valeurs) {
        BigDecimal[] m = MoteurBudgetaire.zeros();
        for (int i = 0; i < valeurs.length; i++) {
            m[i] = bd(valeurs[i]);
        }
        return m;
    }

    // ---------------------------------------------------------------------
    // Ventilation
    // ---------------------------------------------------------------------

    @Test
    void parts_egales_exactes() {
        BigDecimal[] m = MoteurBudgetaire.repartirUniformement(bd("1200"));
        assertThat(m).hasSize(12).allSatisfy(v -> assertThat(v).isEqualByComparingTo("100"));
    }

    @Test
    void les_centimes_restants_vont_sur_decembre() {
        BigDecimal[] m = MoteurBudgetaire.repartirUniformement(bd("100"));
        assertThat(Arrays.copyOf(m, 11)).allSatisfy(v -> assertThat(v).isEqualByComparingTo("8.33"));
        assertThat(m[11]).isEqualByComparingTo("8.37");
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("100");
    }

    @Test
    void la_somme_des_mois_est_toujours_le_montant_annuel() {
        Random r = new Random(42);
        for (int essai = 0; essai < 2000; essai++) {
            BigDecimal annuel = BigDecimal.valueOf(r.nextInt(100_000_000), 2);
            assertThat(MoteurBudgetaire.total(MoteurBudgetaire.repartirUniformement(annuel))).isEqualByComparingTo(annuel);
            BigDecimal[] poids = new BigDecimal[12];
            for (int i = 0; i < 12; i++) {
                poids[i] = BigDecimal.valueOf(r.nextInt(5000), 2);
            }
            BigDecimal[] m = MoteurBudgetaire.repartirSelonProfil(annuel, poids);
            assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo(annuel);
            assertThat(m).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
        }
    }

    @Test
    void le_profil_reproduit_la_saisonnalite() {
        // Realise de l'annee precedente : tout en mars et en septembre, deux fois plus en septembre.
        BigDecimal[] profil = mois("0", "0", "100", "0", "0", "0", "0", "0", "200");
        BigDecimal[] m = MoteurBudgetaire.repartirSelonProfil(bd("3000"), profil);
        assertThat(m[2]).isEqualByComparingTo("1000");
        assertThat(m[8]).isEqualByComparingTo("2000");
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("3000");
    }

    @Test
    void sans_profil_exploitable_la_repartition_est_uniforme() {
        assertThat(MoteurBudgetaire.repartirSelonProfil(bd("1200"), MoteurBudgetaire.zeros())).allSatisfy(v -> assertThat(v).isEqualByComparingTo("100"));
        assertThat(MoteurBudgetaire.repartirSelonProfil(bd("1200"), null)).allSatisfy(v -> assertThat(v).isEqualByComparingTo("100"));
        assertThat(MoteurBudgetaire.repartirSelonProfil(bd("1200"), new BigDecimal[] {BigDecimal.ONE})).allSatisfy(v -> assertThat(v).isEqualByComparingTo("100"));
    }

    // ---------------------------------------------------------------------
    // Periodes
    // ---------------------------------------------------------------------

    @Test
    void trimestres_semestres_et_annee_sont_des_totaux_des_mois() {
        BigDecimal[] m = mois("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(MoteurBudgetaire.trimestres(m)).containsExactly(bd("6"), bd("15"), bd("24"), bd("33"));
        assertThat(MoteurBudgetaire.semestres(m)).containsExactly(bd("21"), bd("57"));
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("78");
        assertThat(MoteurBudgetaire.cumul(m, 3)).isEqualByComparingTo("6");
        assertThat(MoteurBudgetaire.cumul(m, 0)).isEqualByComparingTo("0");
        assertThat(MoteurBudgetaire.cumul(m, 15)).isEqualByComparingTo("78");
    }

    @Test
    void table_mois_vers_douze_montants() {
        BigDecimal[] m = MoteurBudgetaire.mensuelDepuis(Map.of(1, bd("10"), 12, bd("5"), 13, bd("99")));
        assertThat(m[0]).isEqualByComparingTo("10");
        assertThat(m[11]).isEqualByComparingTo("5");
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("15");
    }

    // ---------------------------------------------------------------------
    // Comptes
    // ---------------------------------------------------------------------

    @Test
    void comptes_budgetables_selon_le_syscohada() {
        assertThat(MoteurBudgetaire.estBudgetable("604", 6)).isTrue();
        assertThat(MoteurBudgetaire.estBudgetable("7011", 7)).isTrue();
        assertThat(MoteurBudgetaire.estBudgetable("244", 2)).isTrue();
        assertThat(MoteurBudgetaire.estBudgetable("82", 8)).isTrue();
        assertThat(MoteurBudgetaire.estBudgetable("2845", 2)).as("amortissements").isFalse();
        assertThat(MoteurBudgetaire.estBudgetable("291", 2)).as("depreciations").isFalse();
        assertThat(MoteurBudgetaire.estBudgetable("4011", 4)).isFalse();
        assertThat(MoteurBudgetaire.estBudgetable("571", 5)).isFalse();
        assertThat(MoteurBudgetaire.estBudgetable("6", 6)).as("classe entiere").isFalse();
    }

    @Test
    void depenses_soumises_au_controle() {
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("6042", 6, TypeCompte.CHARGE)).isTrue();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("2441", 2, TypeCompte.ACTIF)).isTrue();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("811", 8, TypeCompte.CHARGE)).isTrue();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("821", 8, TypeCompte.PRODUIT)).isFalse();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("4011", 4, TypeCompte.PASSIF)).as("reglement de dette").isFalse();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("4211", 4, TypeCompte.ACTIF)).as("avance au personnel").isFalse();
        assertThat(MoteurBudgetaire.estDepenseBudgetaire("7011", 7, TypeCompte.PRODUIT)).isFalse();
    }

    @Test
    void une_depense_se_range_sur_la_ligne_la_plus_precise() {
        List<String> lignes = List.of("60", "604", "6042", "622");
        assertThat(MoteurBudgetaire.ligneCouvrant("6042", lignes)).isEqualTo("6042");
        assertThat(MoteurBudgetaire.ligneCouvrant("6042.14", lignes)).isEqualTo("6042");
        assertThat(MoteurBudgetaire.ligneCouvrant("6041", lignes)).isEqualTo("604");
        assertThat(MoteurBudgetaire.ligneCouvrant("6011", lignes)).isEqualTo("60");
        assertThat(MoteurBudgetaire.ligneCouvrant("6221", lignes)).isEqualTo("622");
        assertThat(MoteurBudgetaire.ligneCouvrant("661", lignes)).isNull();
    }

    @Test
    void deux_lignes_qui_se_recouvrent_sont_detectees() {
        List<String[]> conflits = MoteurBudgetaire.chevauchements(List.of("604", "6042", "622", "701", "604"));
        // 604/6042, 604/604 (doublon) et 6042/604 (le second 604) : le plus court est toujours cite en premier.
        assertThat(conflits).extracting(c -> c[0] + ">" + c[1]).containsExactlyInAnyOrder("604>6042", "604>604", "604>6042");
        assertThat(MoteurBudgetaire.chevauchements(List.of("604", "605", "622"))).isEmpty();
    }

    @Test
    void sens_du_realise_selon_la_nature() {
        assertThat(MoteurBudgetaire.realise(TypeCompte.CHARGE, bd("100"), bd("30"))).isEqualByComparingTo("70");
        assertThat(MoteurBudgetaire.realise(TypeCompte.ACTIF, bd("500"), bd("0"))).isEqualByComparingTo("500");
        assertThat(MoteurBudgetaire.realise(TypeCompte.PRODUIT, bd("10"), bd("250"))).isEqualByComparingTo("240");
    }

    @Test
    void sections_et_natures() {
        assertThat(MoteurBudgetaire.section("6042", TypeCompte.CHARGE)).isEqualTo(MoteurBudgetaire.Section.CHARGES);
        assertThat(MoteurBudgetaire.section("7011", TypeCompte.PRODUIT)).isEqualTo(MoteurBudgetaire.Section.PRODUITS);
        assertThat(MoteurBudgetaire.section("2441", TypeCompte.ACTIF)).isEqualTo(MoteurBudgetaire.Section.INVESTISSEMENTS);
        assertThat(MoteurBudgetaire.section("821", TypeCompte.PRODUIT)).isEqualTo(MoteurBudgetaire.Section.PRODUITS);
        assertThat(MoteurBudgetaire.nature("6042")).isEqualTo("60");
        assertThat(MoteurBudgetaire.nature("66")).isEqualTo("66");
    }

    // ---------------------------------------------------------------------
    // Controle
    // ---------------------------------------------------------------------

    @Test
    void disponible_cumule_a_fin_de_mois() {
        BigDecimal[] prevu = MoteurBudgetaire.repartirUniformement(bd("1200"));   // 100 par mois
        BigDecimal[] realise = mois("80", "120", "50");                             // 250 a fin mars
        MoteurBudgetaire.Disponibilite d = MoteurBudgetaire.disponibilite(prevu, realise, bd("20"), 3);
        assertThat(d.prevuCumule()).isEqualByComparingTo("300");
        assertThat(d.realiseCumule()).isEqualByComparingTo("250");
        assertThat(d.disponibleCumule()).isEqualByComparingTo("30");
        assertThat(d.disponibleAnnuel()).isEqualByComparingTo("930");
        assertThat(MoteurBudgetaire.statut(bd("30"), d)).isEqualTo(StatutControleBudget.CONFORME);
        assertThat(MoteurBudgetaire.statut(bd("30.01"), d)).isEqualTo(StatutControleBudget.DEPASSEMENT);
    }

    @Test
    void une_depense_de_decembre_dispose_de_toute_l_annee() {
        BigDecimal[] prevu = MoteurBudgetaire.repartirUniformement(bd("1200"));
        MoteurBudgetaire.Disponibilite d = MoteurBudgetaire.disponibilite(prevu, MoteurBudgetaire.zeros(), BigDecimal.ZERO, 12);
        assertThat(d.disponibleCumule()).isEqualByComparingTo("1200");
        assertThat(MoteurBudgetaire.statut(bd("1200"), d)).isEqualTo(StatutControleBudget.CONFORME);
    }

    @Test
    void gravite_et_justification_des_statuts() {
        assertThat(StatutControleBudget.SANS_BUDGET.gravite()).isGreaterThan(StatutControleBudget.HORS_BUDGET.gravite());
        assertThat(StatutControleBudget.HORS_BUDGET.gravite()).isGreaterThan(StatutControleBudget.DEPASSEMENT.gravite());
        assertThat(StatutControleBudget.DEPASSEMENT.gravite()).isGreaterThan(StatutControleBudget.CONFORME.gravite());
        assertThat(StatutControleBudget.CONFORME.exigeJustification()).isFalse();
        assertThat(StatutControleBudget.NON_CONCERNE.exigeJustification()).isFalse();
        assertThat(StatutControleBudget.DEPASSEMENT.exigeJustification()).isTrue();
        assertThat(StatutControleBudget.HORS_BUDGET.exigeJustification()).isTrue();
        assertThat(StatutControleBudget.SANS_BUDGET.exigeJustification()).isTrue();
    }

    @Test
    void taux_d_execution() {
        assertThat(MoteurBudgetaire.taux(bd("250"), bd("1000"))).isEqualByComparingTo("25.0");
        assertThat(MoteurBudgetaire.taux(bd("1"), bd("3"))).isEqualByComparingTo("33.3");
        assertThat(MoteurBudgetaire.taux(bd("10"), BigDecimal.ZERO)).isNull();
    }
}

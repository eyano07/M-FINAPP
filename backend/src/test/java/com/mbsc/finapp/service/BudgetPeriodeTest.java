package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.enums.DureeBudget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Budgets d'une periode plus courte que l'annee (semestre, trimestre, mois) : mois couverts, libelles, ventilation
 * limitee aux mois de la periode, chevauchement de deux budgets.
 */
class BudgetPeriodeTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Budget budget(DureeBudget duree, int periode, int exercice) {
        return Budget.builder().exercice(exercice).duree(duree).periode(periode).build();
    }

    // ---------------------------------------------------------------------
    // Duree et periode
    // ---------------------------------------------------------------------

    @Test
    void mois_couverts_selon_la_duree() {
        assertThat(DureeBudget.ANNUEL.moisDebut(1)).isEqualTo(1);
        assertThat(DureeBudget.ANNUEL.moisFin(1)).isEqualTo(12);
        assertThat(DureeBudget.SEMESTRIEL.moisDebut(2)).isEqualTo(7);
        assertThat(DureeBudget.SEMESTRIEL.moisFin(2)).isEqualTo(12);
        assertThat(DureeBudget.TRIMESTRIEL.moisDebut(4)).isEqualTo(10);
        assertThat(DureeBudget.TRIMESTRIEL.moisFin(4)).isEqualTo(12);
        assertThat(DureeBudget.TRIMESTRIEL.moisDebut(2)).isEqualTo(4);
        assertThat(DureeBudget.TRIMESTRIEL.moisFin(2)).isEqualTo(6);
        assertThat(DureeBudget.MENSUEL.moisDebut(12)).isEqualTo(12);
        assertThat(DureeBudget.MENSUEL.moisFin(12)).isEqualTo(12);
    }

    @Test
    void periodes_valides_et_invalides() {
        assertThat(DureeBudget.ANNUEL.periodeValide(1)).isTrue();
        assertThat(DureeBudget.ANNUEL.periodeValide(2)).isFalse();
        assertThat(DureeBudget.SEMESTRIEL.periodeValide(2)).isTrue();
        assertThat(DureeBudget.SEMESTRIEL.periodeValide(3)).isFalse();
        assertThat(DureeBudget.TRIMESTRIEL.periodeValide(4)).isTrue();
        assertThat(DureeBudget.TRIMESTRIEL.periodeValide(5)).isFalse();
        assertThat(DureeBudget.MENSUEL.periodeValide(12)).isTrue();
        assertThat(DureeBudget.MENSUEL.periodeValide(0)).isFalse();
        assertThat(DureeBudget.MENSUEL.periodeValide(13)).isFalse();
        assertThatThrownBy(() -> DureeBudget.TRIMESTRIEL.moisDebut(5)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("de 1 à 4");
    }

    @Test
    void libelles_des_periodes() {
        assertThat(DureeBudget.ANNUEL.libelle(1, 2026)).isEqualTo("Exercice 2026");
        assertThat(DureeBudget.SEMESTRIEL.libelle(1, 2026)).isEqualTo("1er semestre 2026");
        assertThat(DureeBudget.SEMESTRIEL.libelle(2, 2026)).isEqualTo("2e semestre 2026");
        assertThat(DureeBudget.TRIMESTRIEL.libelle(4, 2026)).isEqualTo("4e trimestre 2026");
        assertThat(DureeBudget.MENSUEL.libelle(12, 2026)).isEqualTo("Décembre 2026");
        assertThat(DureeBudget.MENSUEL.libelle(8, 2027)).isEqualTo("Août 2027");
    }

    @Test
    void premier_et_dernier_jour_de_la_periode() {
        Budget decembre = budget(DureeBudget.MENSUEL, 12, 2026);
        assertThat(decembre.premierJour()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(decembre.dernierJour()).isEqualTo(LocalDate.of(2026, 12, 31));
        Budget fevrier = budget(DureeBudget.MENSUEL, 2, 2028);
        assertThat(fevrier.dernierJour()).isEqualTo(LocalDate.of(2028, 2, 29));
        Budget t1 = budget(DureeBudget.TRIMESTRIEL, 1, 2027);
        assertThat(t1.premierJour()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(t1.dernierJour()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(t1.couvreMois(3)).isTrue();
        assertThat(t1.couvreMois(4)).isFalse();
    }

    @Test
    void chevauchement_de_deux_budgets() {
        Budget annuel = budget(DureeBudget.ANNUEL, 1, 2026);
        Budget decembre = budget(DureeBudget.MENSUEL, 12, 2026);
        Budget t3 = budget(DureeBudget.TRIMESTRIEL, 3, 2026);
        Budget s2 = budget(DureeBudget.SEMESTRIEL, 2, 2026);
        Budget janvierSuivant = budget(DureeBudget.MENSUEL, 1, 2027);
        assertThat(annuel.chevauche(decembre)).isTrue();
        assertThat(decembre.chevauche(annuel)).isTrue();
        assertThat(t3.chevauche(decembre)).isFalse();
        assertThat(s2.chevauche(t3)).isTrue();
        assertThat(s2.chevauche(decembre)).isTrue();
        assertThat(decembre.chevauche(janvierSuivant)).isFalse();
        assertThat(annuel.chevauche(janvierSuivant)).isFalse();
    }

    // ---------------------------------------------------------------------
    // Ventilation sur les mois de la periode
    // ---------------------------------------------------------------------

    @Test
    void un_budget_de_decembre_met_tout_sur_decembre() {
        BigDecimal[] m = MoteurBudgetaire.repartirUniformement(bd("1500"), 12, 12);
        assertThat(Arrays.copyOf(m, 11)).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(m[11]).isEqualByComparingTo("1500");
    }

    @Test
    void parts_egales_sur_un_trimestre_le_reste_sur_son_dernier_mois() {
        BigDecimal[] m = MoteurBudgetaire.repartirUniformement(bd("100"), 4, 6);
        assertThat(m[3]).isEqualByComparingTo("33.33");
        assertThat(m[4]).isEqualByComparingTo("33.33");
        assertThat(m[5]).isEqualByComparingTo("33.34");
        assertThat(MoteurBudgetaire.moisHorsPeriode(m, 4, 6)).isEmpty();
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("100");
    }

    @Test
    void saisonnalite_limitee_aux_mois_de_la_periode() {
        BigDecimal[] poids = MoteurBudgetaire.zeros();
        poids[0] = bd("1000");   // janvier : hors du 2e semestre, ignore
        poids[6] = bd("10");     // juillet
        poids[11] = bd("30");    // decembre
        BigDecimal[] m = MoteurBudgetaire.repartirSelonProfil(bd("400"), poids, 7, 12);
        assertThat(m[0]).isEqualByComparingTo("0");
        assertThat(m[6]).isEqualByComparingTo("100");
        assertThat(m[11]).isEqualByComparingTo("300");
        assertThat(MoteurBudgetaire.total(m)).isEqualByComparingTo("400");
    }

    @Test
    void sans_historique_sur_la_periode_la_repartition_est_uniforme() {
        BigDecimal[] poids = MoteurBudgetaire.zeros();
        poids[0] = bd("1000");   // seul janvier a un historique
        BigDecimal[] m = MoteurBudgetaire.repartirSelonProfil(bd("90"), poids, 10, 12);
        assertThat(m[9]).isEqualByComparingTo("30");
        assertThat(m[10]).isEqualByComparingTo("30");
        assertThat(m[11]).isEqualByComparingTo("30");
    }

    @Test
    void la_somme_reste_exacte_pour_toute_periode() {
        Random r = new Random(7);
        for (DureeBudget d : DureeBudget.values()) {
            for (int p = 1; p <= d.nombrePeriodes(); p++) {
                int debut = d.moisDebut(p);
                int fin = d.moisFin(p);
                for (int essai = 0; essai < 200; essai++) {
                    BigDecimal montant = BigDecimal.valueOf(r.nextInt(50_000_000), 2);
                    BigDecimal[] u = MoteurBudgetaire.repartirUniformement(montant, debut, fin);
                    assertThat(MoteurBudgetaire.total(u)).isEqualByComparingTo(montant);
                    assertThat(MoteurBudgetaire.moisHorsPeriode(u, debut, fin)).isEmpty();
                    assertThat(u).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
                    BigDecimal[] poids = new BigDecimal[12];
                    for (int i = 0; i < 12; i++) {
                        poids[i] = BigDecimal.valueOf(r.nextInt(5000), 2);
                    }
                    BigDecimal[] s = MoteurBudgetaire.repartirSelonProfil(montant, poids, debut, fin);
                    assertThat(MoteurBudgetaire.total(s)).isEqualByComparingTo(montant);
                    assertThat(MoteurBudgetaire.moisHorsPeriode(s, debut, fin)).isEmpty();
                    assertThat(s).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
                }
            }
        }
    }

    @Test
    void mois_hors_periode_detectes() {
        BigDecimal[] m = MoteurBudgetaire.zeros();
        m[0] = bd("5");
        m[11] = bd("10");
        assertThat(MoteurBudgetaire.moisHorsPeriode(m, 12, 12)).containsExactly(1);
        assertThat(MoteurBudgetaire.moisHorsPeriode(m, 1, 12)).isEmpty();
        assertThat(MoteurBudgetaire.moisHorsPeriode(m, 4, 6)).containsExactly(1, 12);
        assertThat(List.of(MoteurBudgetaire.repartirUniformement(bd("12")))).hasSize(12);
    }

    @Test
    void une_periode_inversee_est_refusee() {
        assertThatThrownBy(() -> MoteurBudgetaire.repartirUniformement(bd("10"), 6, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoteurBudgetaire.repartirUniformement(bd("10"), 0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoteurBudgetaire.repartirUniformement(bd("10"), 1, 13)).isInstanceOf(IllegalArgumentException.class);
    }
}

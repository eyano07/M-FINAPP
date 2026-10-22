package com.mbsc.finapp.domain.enums;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Bornes d'un rapport de caisse : le jour, la semaine du lundi au dimanche, le mois civil. */
class PeriodeRapportTest {

    private static PeriodeRapport.Bornes bornes(PeriodeRapport p, String date) {
        return p.bornes(LocalDate.parse(date));
    }

    @Test
    void le_jour_est_la_date_elle_meme() {
        assertThat(bornes(PeriodeRapport.JOUR, "2026-10-21")).isEqualTo(
            new PeriodeRapport.Bornes(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-21")));
    }

    @Test
    void la_semaine_va_du_lundi_au_dimanche_quel_que_soit_le_jour_de_reference() {
        PeriodeRapport.Bornes attendues = new PeriodeRapport.Bornes(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-25"));
        for (String jour : new String[] {"2026-10-19", "2026-10-21", "2026-10-25"}) {   // lundi, mercredi, dimanche
            assertThat(bornes(PeriodeRapport.SEMAINE, jour)).as(jour).isEqualTo(attendues);
        }
    }

    @Test
    void une_semaine_peut_chevaucher_deux_mois_et_deux_annees() {
        assertThat(bornes(PeriodeRapport.SEMAINE, "2026-10-29")).isEqualTo(
            new PeriodeRapport.Bornes(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-01")));
        assertThat(bornes(PeriodeRapport.SEMAINE, "2026-12-31")).isEqualTo(
            new PeriodeRapport.Bornes(LocalDate.parse("2026-12-28"), LocalDate.parse("2027-01-03")));
    }

    @Test
    void le_mois_va_du_premier_au_dernier_jour_annees_bissextiles_comprises() {
        assertThat(bornes(PeriodeRapport.MOIS, "2026-10-21")).isEqualTo(
            new PeriodeRapport.Bornes(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")));
        assertThat(bornes(PeriodeRapport.MOIS, "2028-02-10").au()).isEqualTo(LocalDate.parse("2028-02-29"));
        assertThat(bornes(PeriodeRapport.MOIS, "2026-02-28").au()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(bornes(PeriodeRapport.MOIS, "2026-12-01").au()).isEqualTo(LocalDate.parse("2026-12-31"));
    }
}

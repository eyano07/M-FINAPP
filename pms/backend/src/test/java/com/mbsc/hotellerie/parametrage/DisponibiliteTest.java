package com.mbsc.hotellerie.parametrage;

import com.mbsc.hotellerie.reservation.Durees;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3 : unité hors service datée, forfaits par créneau. */
class DisponibiliteTest {

    private static OffsetDateTime t(String iso) {
        return OffsetDateTime.parse(iso);
    }

    private static ParametrageService.Unite unite(boolean hs, String du, String au) {
        return new ParametrageService.Unite(1L, 1L, "Hôtel", 1L, "Double", "101", "1", 2, null, "PROPRE", hs, "Climatiseur", null, 0, true,
            du == null ? null : t(du), au == null ? null : t(au), null);
    }

    @Test
    void horsServiceDateNeBloqueQueSaPeriode() {
        var u = unite(true, "2026-11-20T08:00+02:00", "2026-11-23T18:00+02:00");
        assertThat(u.bloquee(t("2026-11-21T14:00+02:00"), t("2026-11-22T12:00+02:00"))).isTrue();
        // Séjour qui chevauche le début ou la fin des travaux : bloqué.
        assertThat(u.bloquee(t("2026-11-19T14:00+02:00"), t("2026-11-20T12:00+02:00"))).isTrue();
        assertThat(u.bloquee(t("2026-11-23T14:00+02:00"), t("2026-11-24T12:00+02:00"))).isTrue();
        // Séjour qui commence après la fin prévue, ou finit avant le début : possible.
        assertThat(u.bloquee(t("2026-11-23T18:00+02:00"), t("2026-11-25T12:00+02:00"))).isFalse();
        assertThat(u.bloquee(t("2026-11-18T14:00+02:00"), t("2026-11-20T08:00+02:00"))).isFalse();
    }

    @Test
    void horsServiceSansFinBloqueTout() {
        var u = unite(true, "2026-11-20T08:00+02:00", null);
        assertThat(u.bloquee(t("2027-03-01T14:00+02:00"), t("2027-03-02T12:00+02:00"))).isTrue();
        assertThat(unite(false, null, null).bloquee(t("2026-11-21T14:00+02:00"), t("2026-11-22T12:00+02:00"))).isFalse();
    }

    @Test
    void plageDeForfaitQuiPasseMinuit() {
        var nuit = new ParametrageService.Tarif(1L, 1L, "Double", 1L, "CRENEAU", new BigDecimal("25"), "Nuit motel", null, null, true, 10,
            LocalTime.of(20, 0), LocalTime.of(2, 0));
        assertThat(nuit.couvre(LocalTime.of(23, 30))).isTrue();
        assertThat(nuit.couvre(LocalTime.of(1, 15))).isTrue();
        assertThat(nuit.couvre(LocalTime.of(14, 0))).isFalse();
        var sieste = new ParametrageService.Tarif(2L, 1L, "Double", 1L, "CRENEAU", new BigDecimal("10"), "Sieste 3 h", null, null, true, 3,
            LocalTime.of(10, 0), LocalTime.of(18, 0));
        assertThat(sieste.couvre(LocalTime.of(12, 0))).isTrue();
        assertThat(sieste.couvre(LocalTime.of(18, 0))).isFalse();
        var toujours = new ParametrageService.Tarif(3L, 1L, "Double", 1L, "CRENEAU", new BigDecimal("8"), "2 h", null, null, true, 2, null, null);
        assertThat(toujours.couvre(LocalTime.of(4, 0))).isTrue();
    }

    @Test
    void forfaitFactureUneUnite() {
        assertThat(Durees.quantite("CRENEAU", t("2026-11-19T22:00+02:00"), t("2026-11-20T08:00+02:00"))).isEqualTo(1);
        assertThat(Durees.libelle("CRENEAU", 1)).isEqualTo("1 forfait");
    }
}

package com.mbsc.hotellerie.reservation;

import com.mbsc.hotellerie.finapp.Signature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DureesTest {

    private static OffsetDateTime t(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    void nuiteesSelonLesDatesDArriveeEtDeDepart() {
        assertThat(Durees.quantite("NUIT", t("2026-11-19T14:00+01:00"), t("2026-11-22T12:00+01:00"))).isEqualTo(3);
        // Arrivée tardive après minuit, départ le jour même : une nuitée au moins.
        assertThat(Durees.quantite("NUIT", t("2026-11-19T01:00+01:00"), t("2026-11-19T11:00+01:00"))).isEqualTo(1);
    }

    @Test
    void heuresEtSemainesEtMoisEntames() {
        assertThat(Durees.quantite("HEURE", t("2026-11-19T10:00+01:00"), t("2026-11-19T12:10+01:00"))).isEqualTo(3);
        assertThat(Durees.quantite("HEURE", t("2026-11-19T10:00+01:00"), t("2026-11-19T12:00+01:00"))).isEqualTo(2);
        assertThat(Durees.quantite("SEMAINE", t("2026-11-01T14:00+01:00"), t("2026-11-09T12:00+01:00"))).isEqualTo(2);
        assertThat(Durees.quantite("MOIS", t("2026-11-01T14:00+01:00"), t("2026-12-01T12:00+01:00"))).isEqualTo(1);
        assertThat(Durees.quantite("MOIS", t("2026-11-01T14:00+01:00"), t("2026-12-15T12:00+01:00"))).isEqualTo(2);
    }

    @Test
    void montantsArrondisCommeMFinapp() {
        BigDecimal ht = Durees.ht(new BigDecimal("33.333"), BigDecimal.valueOf(3));
        assertThat(ht).isEqualByComparingTo("100.00");
        assertThat(Durees.tva(ht, new BigDecimal("16"), true)).isEqualByComparingTo("16.00");
        assertThat(Durees.tva(ht, new BigDecimal("16"), false)).isEqualByComparingTo("0");
        assertThat(Durees.libelle("NUIT", 3)).isEqualTo("3 nuits");
        assertThat(Durees.libelle("MOIS", 2)).isEqualTo("2 mois");
    }

    @Test
    void cleDAppairageLueEtSignatureStable() {
        Signature.Cle cle = Signature.lire(" MBSC1.123e4567-e89b-12d3-a456-426614174000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF \n");
        assertThat(cle.appairageId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        String s1 = Signature.signer(cle.secret(), cle.appairageId(), 1L, "n", "GET", "/pms/moi", new byte[0]);
        String s2 = Signature.signer(cle.secret(), cle.appairageId(), 1L, "n", "get", "/pms/moi", null);
        assertThat(s1).isEqualTo(s2).hasSize(64);
        assertThatThrownBy(() -> Signature.lire("pas une clé")).isInstanceOf(IllegalArgumentException.class);
    }
}

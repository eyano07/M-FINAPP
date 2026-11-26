package com.mbsc.finapp.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Comptes admis en contrepartie d'un décaissement (note de frais). */
class ContrepartieTresorerieTest {

    @ParameterizedTest
    @ValueSource(strings = {"6041", "62891", "6384", "6588", "661", "2443", "2411", "275", "401", "421", "4441", "462", "471",
        "4812", "162", "17", "831", "871", "891"})
    void admis(String compte) {
        assertThat(ContrepartieTresorerie.decaissementAdmis(compte)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1051", "101", "121", "131", "14", "151", "191", "2844", "2911", "311", "4911", "521", "571",
        "585", "681", "6813", "691", "701", "7588", "811", "821", "851", "", "6"})
    void refuses(String compte) {
        assertThat(ContrepartieTresorerie.decaissementAdmis(compte)).isFalse();
    }
}

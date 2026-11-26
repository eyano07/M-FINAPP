package com.mbsc.finapp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExerciceConsulteFilterTest {

    @Test
    void lecturesEtImpressionsPermises() {
        assertThat(ExerciceConsulteFilter.estPermise("GET", "/comptabilite/etats-financiers/export-pdf")).isTrue();
        assertThat(ExerciceConsulteFilter.estPermise("GET", "/caisse/journal")).isTrue();
        assertThat(ExerciceConsulteFilter.estPermise("POST", "/comptabilite/etats-financiers/analyse-ia")).isTrue();
        assertThat(ExerciceConsulteFilter.estPermise("POST", "/auth/refresh")).isTrue();
        assertThat(ExerciceConsulteFilter.estPermise("PUT", "/profil/preferences")).isTrue();
        assertThat(ExerciceConsulteFilter.estPermise("PATCH", "/notifications/3/lue")).isTrue();
    }

    @Test
    void saisiesRefusees() {
        assertThat(ExerciceConsulteFilter.estPermise("POST", "/comptabilite/pieces")).isFalse();
        assertThat(ExerciceConsulteFilter.estPermise("POST", "/caisse/operations")).isFalse();
        assertThat(ExerciceConsulteFilter.estPermise("PUT", "/notes-frais/4/objet")).isFalse();
        assertThat(ExerciceConsulteFilter.estPermise("DELETE", "/comptabilite/pieces/9")).isFalse();
    }

    @Test
    void enteteLu() {
        assertThat(ExerciceConsulteFilter.annee("2025")).isEqualTo(2025);
        assertThat(ExerciceConsulteFilter.annee(null)).isNull();
        assertThat(ExerciceConsulteFilter.annee("abc")).isNull();
    }
}

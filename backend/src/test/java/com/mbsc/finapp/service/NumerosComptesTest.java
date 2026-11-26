package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Un journal ancien porte des sous-comptes à point : l'import les lit sans point (audit SYSCOHADA, C-17). */
class NumerosComptesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NumerosComptes numeros = new NumerosComptes(jdbc);

    @Test
    void un_compte_a_point_perd_ses_points() {
        assertThat(numeros.sansPoint("6042.15")).isEqualTo("604215");
        assertThat(numeros.sansPoint("2728.95.6")).isEqualTo("2728956");
        assertThat(numeros.sansPoint("6042.10")).isEqualTo("604210");
    }

    @Test
    void un_compte_renumerote_par_la_migration_reprend_son_nouveau_numero() {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), eq(String.class), eq("431.1")))
            .thenReturn(List.of("4315"));
        assertThat(numeros.sansPoint("431.1")).isEqualTo("4315");
    }

    @Test
    void un_compte_sans_point_est_inchange() {
        assertThat(numeros.sansPoint("4011")).isEqualTo("4011");
    }
}

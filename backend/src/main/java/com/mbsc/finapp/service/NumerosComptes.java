package com.mbsc.finapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Numéro de compte sans point (audit SYSCOHADA du 10/11/2026, C-17) : un journal exporté avant V137 porte encore
 * des sous-comptes à point (6042.15, 431.1). Un numéro renuméroté par V137 reprend son nouveau numéro (431.1 -> 4315,
 * table {@code comptes_anciens_numeros}) ; tout autre numéro perd simplement ses points (6042.15 -> 604215).
 */
@Component
@RequiredArgsConstructor
public class NumerosComptes {

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String sansPoint(String numero) {
        if (numero == null || numero.indexOf('.') < 0) return numero;
        String n = numero.trim();
        return cache.computeIfAbsent(n, cle -> {
            List<String> l = jdbc.queryForList("select nouveau from comptes_anciens_numeros where ancien = ?", String.class, cle);
            return l.isEmpty() ? cle.replace(".", "") : l.get(0);
        });
    }
}

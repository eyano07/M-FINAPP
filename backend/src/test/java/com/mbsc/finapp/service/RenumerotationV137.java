package com.mbsc.finapp.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les migrations V120 et V121 ont créé des sous-comptes à point (6276.1, 736.1...) que V137 renumérote sans point
 * (audit SYSCOHADA du 10/11/2026, C-17). Pour comparer ces migrations au code, les tests appliquent au texte SQL la
 * même table de renumérotation que V137, lue dans la migration elle-même.
 */
final class RenumerotationV137 {

    private static final String MIGRATION = "/db/migration/V137__comptes_sans_point.sql";
    private static final Pattern COUPLE = Pattern.compile("\\('(\\d+\\.\\d+)', '(\\d+)'\\)");

    private RenumerotationV137() {
    }

    static Map<String, String> table() throws IOException {
        try (InputStream in = RenumerotationV137.class.getResourceAsStream(MIGRATION)) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> t = new LinkedHashMap<>();
            Matcher m = COUPLE.matcher(sql);
            while (m.find()) t.put(m.group(1), m.group(2));
            return t;
        }
    }

    /** Le texte avec chaque ancien numéro remplacé par le nouveau (736.1 devient 7361, pas 736.15). */
    static String appliquer(String texte) throws IOException {
        String resultat = texte;
        for (var e : table().entrySet()) {
            resultat = resultat.replaceAll("(?<![0-9.])" + Pattern.quote(e.getKey()) + "(?![0-9])", e.getValue());
        }
        return resultat;
    }
}

package com.mbsc.finapp.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit SYSCOHADA du 10/11/2026, C-24 : des migrations anciennes ont ré-imputé des écritures passées par UPDATE direct.
 * Une écriture comptabilisée ne se corrige que par une pièce (extourne, reclassement) : aucune migration postérieure à
 * V134 ne modifie ni ne supprime une écriture du grand livre ou une pièce comptable. La base le refuse d'ailleurs
 * depuis V135 ; ce test le dit avant qu'une migration n'échoue au démarrage.
 */
class MigrationsSansReecritureComptableTest {

    private static final int DERNIERE_TOLEREE = 134;
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern INTERDIT = Pattern.compile(
        "(?i)\\b(update\\s+grand_livre\\b|delete\\s+from\\s+grand_livre\\b|truncate\\s+(table\\s+)?grand_livre\\b"
            + "|delete\\s+from\\s+pieces_comptables\\b|truncate\\s+(table\\s+)?pieces_comptables\\b)");

    @Test
    void aucune_migration_recente_ne_reecrit_le_grand_livre() throws IOException {
        Path dossier = Path.of("src/main/resources/db/migration");
        List<String> fautives;
        try (Stream<Path> fichiers = Files.list(dossier)) {
            fautives = fichiers
                .filter(p -> {
                    Matcher m = VERSION.matcher(p.getFileName().toString());
                    return m.matches() && Integer.parseInt(m.group(1)) > DERNIERE_TOLEREE;
                })
                .filter(p -> {
                    try {
                        return INTERDIT.matcher(sansCommentaires(Files.readString(p))).find();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(p -> p.getFileName().toString())
                .toList();
        }
        assertThat(fautives).as("migrations qui modifient ou suppriment des écritures comptables").isEmpty();
    }

    private static String sansCommentaires(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}

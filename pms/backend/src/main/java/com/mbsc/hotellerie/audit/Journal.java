package com.mbsc.hotellerie.audit;

import com.mbsc.hotellerie.securite.SessionCourante;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/**
 * Journal des actions de l'hôtellerie, en ajout seul : une ligne en base (consultable) et une ligne dans
 * {@code audit.log} du volume des journaux. Ce fichier ne se supprime jamais (même règle que celui de M-FINAPP).
 */
@Component
public class Journal {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);

    private final JdbcTemplate jdbc;
    private final Path fichier;

    public Journal(JdbcTemplate jdbc, @Value("${app.audit-dir}") String dossier) {
        this.jdbc = jdbc;
        this.fichier = Path.of(dossier, "audit.log");
    }

    public void noter(String action, String objet, String detail) {
        String qui = SessionCourante.auteur();
        jdbc.update("insert into journal (qui, action, objet, detail) values (?, ?, ?, ?)", qui, action, objet, detail);
        String ligne = OffsetDateTime.now() + " | " + qui + " | " + action + " | " + (objet == null ? "" : objet) + " | "
            + (detail == null ? "" : detail.replace('\n', ' ')) + System.lineSeparator();
        try {
            Files.createDirectories(fichier.getParent());
            Files.writeString(fichier, ligne, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Journal d'audit non écrit ({}) : {}", fichier, e.getMessage());
        }
    }
}

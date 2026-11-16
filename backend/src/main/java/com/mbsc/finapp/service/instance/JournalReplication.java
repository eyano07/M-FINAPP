package com.mbsc.finapp.service.instance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Journal des changements du serveur local pas encore recopies vers le serveur en ligne (table {@code journal_replication},
 * remplie par les declencheurs PostgreSQL du mode local).
 */
@Component
public class JournalReplication {

    private final JdbcTemplate jdbc;

    public JournalReplication(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Nombre de changements en attente d'envoi (0 si le journal n'existe pas encore). */
    public long enAttente() {
        Boolean existe = jdbc.queryForObject("select to_regclass('journal_replication') is not null", Boolean.class);
        if (!Boolean.TRUE.equals(existe)) return 0;
        Long n = jdbc.queryForObject("select count(*) from journal_replication", Long.class);
        return n == null ? 0 : n;
    }
}

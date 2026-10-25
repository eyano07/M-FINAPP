package com.mbsc.finapp.service.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Au demarrage (apres Flyway), sur tous les serveurs : chaque table recopiee porte le declencheur de capture
 * {@code replication_capture} (V133 : il ne note rien hors du mode LOCAL), et chaque cle etrangere est differable
 * ({@code DEFERRABLE INITIALLY IMMEDIATE} : rien ne change pour les saisies, mais un lot de synchronisation ou un
 * instantane peut etre applique dans n'importe quel ordre, controle a la fin de la transaction). Les tables creees par
 * les migrations futures sont ainsi couvertes sans y penser. Ne bloque jamais le demarrage : un echec est journalise et
 * signale a l'administrateur ({@link #pret()}), et le mode local ne s'active pas tant que le schema n'est pas pret.
 */
@Component
@Order(0)
public class ReplicationSchemaVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplicationSchemaVerifier.class);
    public static final String DECLENCHEUR = "replication_capture";

    private final JdbcTemplate jdbc;
    private final ReplicationSchema schema;
    private volatile String probleme;
    private volatile boolean verifie;

    public ReplicationSchemaVerifier(JdbcTemplate jdbc, ReplicationSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifier();
    }

    /** Pose les declencheurs manquants et rend differables les cles etrangeres qui ne le sont pas. */
    public synchronized void verifier() {
        StringBuilder erreurs = new StringBuilder();
        schema.oublier();
        int declencheurs = 0;
        for (String table : schema.tables().keySet()) {
            Boolean present = jdbc.queryForObject("select exists (select 1 from pg_trigger where tgrelid = "
                + "to_regclass('public.' || quote_ident(?)) and tgname = ?)", Boolean.class, table, DECLENCHEUR);
            if (Boolean.TRUE.equals(present)) continue;
            try {
                jdbc.execute("create trigger " + DECLENCHEUR + " after insert or update or delete on public."
                    + ReplicationSchema.ident(table) + " for each row execute function replication_capturer()");
                declencheurs++;
            } catch (RuntimeException e) {
                erreurs.append("déclencheur sur ").append(table).append(" : ").append(e.getMessage()).append(" ; ");
            }
        }
        List<Map<String, Object>> fk = jdbc.queryForList(
            "select c.conrelid::regclass::text as tab, c.conname as nom from pg_constraint c "
                + "join pg_namespace n on n.oid = c.connamespace where n.nspname = 'public' and c.contype = 'f' "
                + "and not c.condeferrable");
        int differees = 0;
        for (Map<String, Object> ligne : fk) {
            String tab = (String) ligne.get("tab");
            String nom = (String) ligne.get("nom");
            try {
                jdbc.execute("alter table " + tab + " alter constraint " + ReplicationSchema.ident(nom)
                    + " deferrable initially immediate");
                differees++;
            } catch (RuntimeException e) {
                erreurs.append("clé étrangère ").append(nom).append(" : ").append(e.getMessage()).append(" ; ");
            }
        }
        probleme = erreurs.isEmpty() ? null : erreurs.toString();
        verifie = true;
        if (probleme != null) {
            log.error("Schema de synchronisation incomplet : {}", probleme);
        } else if (declencheurs > 0 || differees > 0) {
            log.info("Schema de synchronisation : {} declencheur(s) pose(s), {} cle(s) etrangere(s) rendue(s) differable(s)",
                declencheurs, differees);
        }
    }

    /** Vrai si le schema est pret pour la synchronisation (declencheurs et cles differables partout). */
    public boolean pret() {
        if (!verifie) verifier();
        return probleme == null;
    }

    /** Explication d'un schema incomplet (null s'il est pret). */
    public String probleme() {
        return probleme;
    }
}

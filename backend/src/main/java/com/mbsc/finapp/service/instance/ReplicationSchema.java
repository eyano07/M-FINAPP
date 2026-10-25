package com.mbsc.finapp.service.instance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tables recopiees du serveur local vers le serveur en ligne, lues dans le catalogue PostgreSQL : colonnes ecrivables
 * (hors colonnes calculees) et cle primaire. Les noms viennent du catalogue, jamais d'un appel : un lot qui cite une
 * table inconnue ou non recopiee est refuse ({@link #table}).
 */
@Component
public class ReplicationSchema {

    /** Tables propres a chaque serveur : jamais recopiees (techniques, ou secrets chiffres avec la cle du serveur). */
    public static final Set<String> NON_RECOPIEES = Set.of(
        "flyway_schema_history", "journal_replication", "parametres_instance", "actions_distantes",
        "actions_distantes_appliquees", "audit_a_envoyer", "parametres_ia", "parametres_mail", "parametres_emcf");

    /** Une table recopiee : colonnes ecrivables (ordre du catalogue) et colonnes de la cle primaire. */
    public record Table(String nom, List<String> colonnes, List<String> cle) {
        public List<String> colonnesHorsCle() {
            return colonnes.stream().filter(c -> !cle.contains(c)).toList();
        }
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Table> cache;

    public ReplicationSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tables recopiees, par nom (ordre alphabetique). */
    public Map<String, Table> tables() {
        Map<String, Table> t = cache;
        if (t == null) {
            t = charger();
            cache = t;
        }
        return t;
    }

    /** Relit le catalogue (apres une migration ou la pose des declencheurs). */
    public void oublier() {
        cache = null;
    }

    /**
     * La table recopiee de ce nom.
     *
     * @throws IllegalArgumentException si elle n'existe pas ou n'est pas recopiee
     */
    public Table table(String nom) {
        Table t = tables().get(nom);
        if (t == null) {
            throw new IllegalArgumentException("Table inconnue ou non recopiée : " + nom);
        }
        return t;
    }

    private Map<String, Table> charger() {
        List<String> noms = jdbc.queryForList(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'public' and c.relkind = 'r' order by c.relname", String.class);
        Map<String, Table> resultat = new LinkedHashMap<>();
        for (String nom : noms) {
            if (NON_RECOPIEES.contains(nom)) continue;
            List<String> colonnes = jdbc.queryForList(
                "select a.attname from pg_attribute a where a.attrelid = to_regclass('public.' || quote_ident(?)) "
                    + "and a.attnum > 0 and not a.attisdropped and a.attgenerated = '' order by a.attnum", String.class, nom);
            List<String> cle = jdbc.queryForList(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey) "
                    + "where i.indrelid = to_regclass('public.' || quote_ident(?)) and i.indisprimary "
                    + "order by array_position(i.indkey::int2[], a.attnum)", String.class, nom);
            if (cle.isEmpty()) continue;   // sans cle primaire, une ligne ne peut pas etre retrouvee : non recopiee
            resultat.put(nom, new Table(nom, List.copyOf(colonnes), List.copyOf(cle)));
        }
        return java.util.Collections.unmodifiableMap(resultat);
    }

    /** Sequences du schema (identifiants et references metier) avec leur valeur : alignees sur le serveur en ligne. */
    public Map<String, Long> valeursSequences() {
        List<String> noms = jdbc.queryForList(
            "select sequencename from pg_sequences where schemaname = 'public' order by 1", String.class);
        Map<String, Long> valeurs = new LinkedHashMap<>();
        for (String nom : noms) {
            // last_value sans is_called : une sequence jamais appelee vaut sa valeur de depart, ce qui ne change rien.
            Boolean appelee = jdbc.queryForObject("select is_called from public." + ident(nom), Boolean.class);
            Long derniere = jdbc.queryForObject("select last_value from public." + ident(nom), Long.class);
            if (Boolean.TRUE.equals(appelee) && derniere != null) valeurs.put(nom, derniere);
        }
        return valeurs;
    }

    /** Identifiant SQL entre guillemets (nom lu dans le catalogue ou verifie contre lui). */
    public static String ident(String nom) {
        return "\"" + nom.replace("\"", "\"\"") + "\"";
    }
}

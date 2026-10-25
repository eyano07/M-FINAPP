package com.mbsc.finapp.service.instance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    private volatile List<String> ordreCache;

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
        ordreCache = null;
    }

    /**
     * Tables recopiees, les tables referencees avant celles qui les referencent (cles etrangeres) : l'ordre d'insertion
     * d'un remplacement complet ; l'ordre inverse est celui de la suppression (les enfants d'abord, pour que les
     * suppressions en cascade n'aient plus rien a faire).
     */
    public List<String> ordreParentsDabord() {
        List<String> o = ordreCache;
        if (o == null) {
            Map<String, Set<String>> parents = new LinkedHashMap<>();
            for (String nom : tables().keySet()) parents.put(nom, new TreeSet<>());
            for (Map<String, Object> l : jdbc.queryForList(
                "select e.relname as enfant, p.relname as parent from pg_constraint c "
                    + "join pg_class e on e.oid = c.conrelid join pg_class p on p.oid = c.confrelid "
                    + "join pg_namespace n on n.oid = c.connamespace "
                    + "where n.nspname = 'public' and c.contype = 'f' and c.conrelid <> c.confrelid")) {
                Set<String> liste = parents.get((String) l.get("enfant"));
                if (liste != null && parents.containsKey((String) l.get("parent"))) liste.add((String) l.get("parent"));
            }
            o = ordonner(parents);
            ordreCache = o;
        }
        return o;
    }

    /**
     * Tri topologique : chaque table apres ses parents (alphabetique a egalite). Les tables prises dans un cycle de cles
     * etrangeres (hors auto-reference) viennent en dernier ; les cles etrangeres etant differees, leur ordre est sans
     * consequence pour l'insertion.
     */
    static List<String> ordonner(Map<String, ? extends Collection<String>> parents) {
        List<String> ordre = new ArrayList<>();
        Set<String> faits = new HashSet<>();
        boolean progres = true;
        while (faits.size() < parents.size() && progres) {
            progres = false;
            for (Map.Entry<String, ? extends Collection<String>> e : parents.entrySet()) {
                if (!faits.contains(e.getKey()) && faits.containsAll(e.getValue())) {
                    ordre.add(e.getKey());
                    faits.add(e.getKey());
                    progres = true;
                }
            }
        }
        for (String nom : parents.keySet()) {
            if (faits.add(nom)) ordre.add(nom);
        }
        return List.copyOf(ordre);
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

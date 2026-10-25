package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Ecriture generique des lignes recopiees (lots du journal, instantane, resynchronisation complete), sans SQL ecrit a la
 * main par table : chaque ligne JSON (produite par {@code to_jsonb} sur l'autre serveur, au meme schema) est convertie
 * par {@code jsonb_populate_record}, qui en type chaque colonne. Les noms de tables et de colonnes viennent du catalogue
 * ({@link ReplicationSchema}). Toutes les methodes s'executent dans la transaction de l'appelant, qui doit avoir differe
 * les cles etrangeres ({@link #differerContraintes}).
 *
 * <p>Fusions particulieres (la copie en ligne a ses propres ecritures sur ces colonnes) :</p>
 * <ul>
 *   <li>{@code users.version_jetons} : chaque serveur garde son propre compteur (ses sessions sont signees par son propre
 *   secret) ; il augmente de un quand le mot de passe change sur l'autre serveur, ce qui ferme aussi les sessions d'ici ;</li>
 *   <li>{@code users.actif} : une activation / desactivation faite sur la copie et pas encore appliquee par le serveur
 *   local l'emporte ;</li>
 *   <li>{@code notifications.lue} : une notification lue sur l'un des deux serveurs reste lue.</li>
 * </ul>
 */
@Component
public class ReplicationApplicateur {

    private final JdbcTemplate jdbc;
    private final ReplicationSchema schema;
    private final Map<String, String> requetesEcriture = new ConcurrentHashMap<>();
    private final Map<String, String> requetesSuppression = new ConcurrentHashMap<>();

    public ReplicationApplicateur(JdbcTemplate jdbc, ReplicationSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** Oublie les requetes preparees (apres une relecture du schema). */
    public void oublier() {
        requetesEcriture.clear();
        requetesSuppression.clear();
    }

    /** Cles etrangeres controlees a la fin de la transaction courante (elles sont toutes differables). */
    public void differerContraintes() {
        jdbc.execute("set constraints all deferred");
    }

    /** Insere ou met a jour une ligne (par sa cle primaire). */
    public void ecrire(String table, String ligneJson) {
        jdbc.update(requeteEcriture(table), ligneJson);
    }

    /** Supprime une ligne (par sa cle primaire). */
    public void supprimer(String table, String ligneJson) {
        jdbc.update(requeteSuppression(table), ligneJson);
    }

    /** Applique un changement du journal : I et U = ecriture, D = suppression. */
    public void appliquer(String table, char operation, JsonNode ligne) {
        if (operation == 'D') {
            supprimer(table, ligne.toString());
        } else {
            ecrire(table, ligne.toString());
        }
    }

    /** Aligne les sequences (identifiants et references metier) sur les valeurs du serveur principal. */
    public void alignerSequences(Map<String, Long> valeurs) {
        if (valeurs == null) return;
        List<String> connues = jdbc.queryForList("select sequencename from pg_sequences where schemaname = 'public'", String.class);
        valeurs.forEach((nom, valeur) -> {
            if (valeur != null && connues.contains(nom)) {
                jdbc.queryForObject("select setval(?::regclass, ?, true)", Long.class, "public." + ReplicationSchema.ident(nom), valeur);
            }
        });
    }

    // ---------------------------------------------------------------------
    // Instantane : remplacement complet d'une table (ce qui n'y est pas est supprime)
    // ---------------------------------------------------------------------

    /** Prepare le remplacement d'une table : zone de transit temporaire (supprimee a la fin de la transaction). */
    public void preparerRemplacement() {
        jdbc.execute("create temp table if not exists _replication_transit (nom_table text not null, ligne jsonb not null) "
            + "on commit drop");
        jdbc.execute("truncate _replication_transit");
    }

    /** Ajoute un paquet de lignes (tableau JSON) d'une table en transit. */
    public void transiter(String table, String tableauJson) {
        schema.table(table);
        jdbc.update("insert into _replication_transit (nom_table, ligne) select ?, x from jsonb_array_elements(?::jsonb) x",
            table, tableauJson);
    }

    /**
     * Remplace le contenu de la table par les lignes en transit : supprime d'abord ce qui n'y figure pas (par cle
     * primaire : ainsi un compte ou un compte comptable de meme email ou numero, mais d'identifiant different, ne gene
     * pas), puis insere ou met a jour les autres (avec les fusions particulieres). Renvoie le nombre de lignes en transit.
     */
    public long remplacer(String table) {
        ReplicationSchema.Table t = schema.table(table);
        String nom = "public." + ReplicationSchema.ident(table);
        String cleTransit = t.cle().stream()
            .map(c -> "'" + c.replace("'", "''") + "', s.ligne -> '" + c.replace("'", "''") + "'")
            .collect(Collectors.joining(", ", "jsonb_build_object(", ")"));
        String cleCible = t.cle().stream()
            .map(c -> "'" + c.replace("'", "''") + "', to_jsonb(x." + ReplicationSchema.ident(c) + ")")
            .collect(Collectors.joining(", ", "jsonb_build_object(", ")"));
        jdbc.update("delete from " + nom + " x where not exists (select 1 from _replication_transit s where s.nom_table = ? "
            + "and " + cleTransit + " = " + cleCible + ")", table);
        String colonnes = t.colonnes().stream().map(ReplicationSchema::ident).collect(Collectors.joining(", "));
        String selection = t.colonnes().stream().map(c -> "r." + ReplicationSchema.ident(c)).collect(Collectors.joining(", "));
        jdbc.update("insert into " + nom + " as cible (" + colonnes + ") select " + selection + " from _replication_transit s, "
            + "jsonb_populate_record(null::" + nom + ", s.ligne) r where s.nom_table = ? " + surConflit(t), table);
        Long n = jdbc.queryForObject("select count(*) from _replication_transit where nom_table = ?", Long.class, table);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------------
    // Requetes
    // ---------------------------------------------------------------------

    private String requeteEcriture(String table) {
        return requetesEcriture.computeIfAbsent(table, nomTable -> {
            ReplicationSchema.Table t = schema.table(nomTable);
            String nom = "public." + ReplicationSchema.ident(nomTable);
            String colonnes = t.colonnes().stream().map(ReplicationSchema::ident).collect(Collectors.joining(", "));
            return "insert into " + nom + " as cible (" + colonnes + ") select " + colonnes
                + " from jsonb_populate_record(null::" + nom + ", ?::jsonb) " + surConflit(t);
        });
    }

    private String requeteSuppression(String table) {
        return requetesSuppression.computeIfAbsent(table, nomTable -> {
            ReplicationSchema.Table t = schema.table(nomTable);
            String nom = "public." + ReplicationSchema.ident(nomTable);
            String egalites = t.cle().stream()
                .map(c -> "x." + ReplicationSchema.ident(c) + " = r." + ReplicationSchema.ident(c))
                .collect(Collectors.joining(" and "));
            return "delete from " + nom + " x using jsonb_populate_record(null::" + nom + ", ?::jsonb) r where " + egalites;
        });
    }

    private static String surConflit(ReplicationSchema.Table t) {
        String cle = t.cle().stream().map(ReplicationSchema::ident).collect(Collectors.joining(", "));
        List<String> autres = t.colonnesHorsCle();
        if (autres.isEmpty()) {
            return "on conflict (" + cle + ") do nothing";
        }
        return "on conflict (" + cle + ") do update set " + autres.stream()
            .map(c -> ReplicationSchema.ident(c) + " = " + fusion(t.nom(), c))
            .collect(Collectors.joining(", "));
    }

    /**
     * Valeur ecrite pour une colonne d'une ligne existante (alias « cible » ; « excluded » = ligne recue) : voir les fusions
     * particulieres en tete de classe.
     */
    static String fusion(String table, String colonne) {
        if (table.equals("users") && colonne.equals("version_jetons")) {
            // Chaque serveur signe ses propres sessions (secret JWT distinct) : son compteur lui reste propre ; seul un
            // changement de mot de passe sur l'autre serveur ferme aussi les sessions ouvertes ici.
            return "cible.version_jetons + case when cible.mot_de_passe is distinct from excluded.mot_de_passe then 1 else 0 end";
        }
        if (table.equals("users") && colonne.equals("actif")) {
            return "coalesce((select (a.contenu ->> 'actif')::boolean from actions_distantes a "
                + "where a.type = 'ACTIVER_UTILISATEUR' and a.statut = 'EN_ATTENTE' and a.cible_id = excluded.id "
                + "order by a.cree_le desc limit 1), excluded.actif)";
        }
        if (table.equals("notifications") && colonne.equals("lue")) {
            return "cible.lue or excluded.lue";
        }
        return "excluded." + ReplicationSchema.ident(colonne);
    }
}

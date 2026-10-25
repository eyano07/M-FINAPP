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
    // Instantane et resynchronisation : remplacement complet de toutes les tables
    // ---------------------------------------------------------------------

    /** Prepare le remplacement : zone de transit temporaire (supprimee a la fin de la transaction). */
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
     * Remplace le contenu de TOUTES les tables recopiees par les lignes en transit : tout est supprime puis insere dans la
     * meme transaction. Un remplacement ligne a ligne (« insert ... on conflict (cle primaire) do update ») ne convient
     * pas : deux bases nees separement peuvent donner les memes identifiants a des lignes differentes (ex. les numeros de
     * comptes comptables), et la mise a jour d'une ligne viole alors une contrainte d'unicite tant que l'autre ligne n'a
     * pas encore change de valeur. Table par table, les enfants sont supprimes avant leurs parents (les suppressions en
     * cascade n'ont plus rien a faire) et les parents inseres avant leurs enfants ; les cles etrangeres sont differees
     * ({@link #differerContraintes}) et controlees a la fin.
     *
     * @param fusionner garde ce que la copie en ligne ecrit elle-meme (voir l'en-tete de classe) : a demander pour la
     *                  resynchronisation d'une copie, pas pour l'activation du mode local (les donnees d'ici sont jetees)
     * @return le nombre de lignes inserees
     */
    public long remplacerToutes(boolean fusionner) {
        List<String> parentsDabord = schema.ordreParentsDabord();
        jdbc.execute("create index _replication_transit_nom on _replication_transit (nom_table)");
        boolean avecFusion = fusionner && schema.tables().containsKey("users") && schema.tables().containsKey("notifications");
        if (avecFusion) {
            jdbc.execute("create temp table _fusion_users on commit drop as select id, version_jetons, mot_de_passe from public.users");
            jdbc.execute("create temp table _fusion_notifications on commit drop as select id from public.notifications where lue");
        }
        for (int i = parentsDabord.size() - 1; i >= 0; i--) {
            jdbc.update("delete from public." + ReplicationSchema.ident(parentsDabord.get(i)));
        }
        long total = 0;
        for (String nom : parentsDabord) {
            total += inserer(nom);
        }
        if (avecFusion) {
            restaurerFusions();
        }
        return total;
    }

    private long inserer(String nomTable) {
        ReplicationSchema.Table t = schema.table(nomTable);
        String nom = "public." + ReplicationSchema.ident(nomTable);
        String colonnes = t.colonnes().stream().map(ReplicationSchema::ident).collect(Collectors.joining(", "));
        String selection = t.colonnes().stream().map(c -> "r." + ReplicationSchema.ident(c)).collect(Collectors.joining(", "));
        return jdbc.update("insert into " + nom + " (" + colonnes + ") select " + selection + " from _replication_transit s, "
            + "jsonb_populate_record(null::" + nom + ", s.ligne) r where s.nom_table = ?", nomTable);
    }

    /** Memes fusions que {@link #fusion} pour les lignes recopiees une a une, appliquees apres un remplacement complet. */
    private void restaurerFusions() {
        jdbc.update("update public.users u set version_jetons = f.version_jetons "
            + "+ case when f.mot_de_passe is distinct from u.mot_de_passe then 1 else 0 end from _fusion_users f where f.id = u.id");
        jdbc.update("update public.users u set actif = (select (a.contenu ->> 'actif')::boolean from public.actions_distantes a "
            + "where a.type = 'ACTIVER_UTILISATEUR' and a.statut = 'EN_ATTENTE' and a.cible_id = u.id "
            + "order by a.cree_le desc limit 1) where exists (select 1 from public.actions_distantes a "
            + "where a.type = 'ACTIVER_UTILISATEUR' and a.statut = 'EN_ATTENTE' and a.cible_id = u.id)");
        jdbc.update("update public.notifications n set lue = true from _fusion_notifications f where f.id = n.id and not n.lue");
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

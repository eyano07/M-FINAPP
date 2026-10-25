package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fichiers references par les donnees recopiees (pieces jointes des notes, photos de profil, logo de l'entreprise) :
 * ils suivent les lignes qui les citent, au meme chemin relatif sur les deux serveurs.
 */
@Component
public class FichiersReplication {

    /** Table -> colonne contenant le chemin relatif d'un fichier stocke. */
    public static final Map<String, String> COLONNES = Map.of(
        "pieces_jointes", "chemin_stockage",
        "users", "photo_chemin_stockage",
        "parametres_entreprise", "logo_chemin_stockage");

    private final JdbcTemplate jdbc;

    public FichiersReplication(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tous les chemins de fichiers cites par la base. */
    public List<String> tousLesChemins() {
        List<String> chemins = new ArrayList<>();
        COLONNES.forEach((table, colonne) -> chemins.addAll(jdbc.queryForList(
            "select distinct " + ReplicationSchema.ident(colonne) + " from public." + ReplicationSchema.ident(table)
                + " where " + ReplicationSchema.ident(colonne) + " is not null", String.class)));
        return chemins;
    }

    /** Chemin de fichier cite par une ligne recopiee (null si la table n'en cite pas ou s'il est vide). */
    public static String cheminDe(String table, JsonNode ligne) {
        String colonne = COLONNES.get(table);
        if (colonne == null || ligne == null) return null;
        JsonNode v = ligne.get(colonne);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}

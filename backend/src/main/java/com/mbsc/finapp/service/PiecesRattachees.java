package com.mbsc.finapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pieces comptables rattachees a une operation de l'application : vente,
 * mouvement de stock, bulletin de paie, reception de minerai, immobilisation,
 * provision, rapprochement, piece extournee...
 *
 * <p>La liste des tables est lue dans le catalogue PostgreSQL (toute cle
 * etrangere vers {@code pieces_comptables}) plutot que figee dans le code :
 * une migration qui rattache une nouvelle table aux pieces est ainsi protegee
 * d'office. Avant cela, l'import en mode « Remplacer » effacait les ecritures
 * d'une piece liee a un mouvement de stock, puis echouait en voulant supprimer
 * la piece (cle etrangere {@code mouvements_stock_piece_id_fkey}) — erreur
 * interne cote ecran, et sans ce garde-fou de la base, une operation privee de
 * sa comptabilite.</p>
 */
@Component
@RequiredArgsConstructor
public class PiecesRattachees {

    /** Cles etrangeres a une colonne vers les pieces, hors grand livre (les ecritures elles-memes). */
    private static final String REFERENCES = """
        select cl.relname, a.attname
        from pg_constraint c
        join pg_class cl on cl.oid = c.conrelid
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
        where c.contype = 'f'
          and c.confrelid = 'pieces_comptables'::regclass
          and array_length(c.conkey, 1) = 1
          and cl.relname <> 'grand_livre'
        """;

    private final JdbcTemplate jdbc;

    /** Identifiants des pieces referencees par au moins une autre table. */
    public Set<Long> identifiants() {
        List<String> selections = jdbc.query(REFERENCES, (rs, i) -> {
            String table = ident(rs.getString(1));
            String colonne = ident(rs.getString(2));
            return "select " + colonne + " from " + table + " where " + colonne + " is not null";
        });
        if (selections.isEmpty()) {
            return new HashSet<>();
        }
        String union = selections.stream().collect(Collectors.joining(" union "));
        return new HashSet<>(jdbc.queryForList(union, Long.class));
    }

    /** Nom issu du catalogue, cite pour supporter majuscules et mots reserves. */
    private static String ident(String nom) {
        return '"' + nom.replace("\"", "\"\"") + '"';
    }
}

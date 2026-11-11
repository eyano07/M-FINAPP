package com.mbsc.finapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Efface toute la comptabilite et les operations qui en produisent, avant un
 * import de journal en mode « Remplacer » : pieces et ecritures, notes de frais,
 * operations de caisse, de banque et de mobile money, ventes, mouvements et
 * niveaux de stock, paie, immobilisations, provisions, minerai... Restent les
 * utilisateurs, les parametres, le plan comptable, les articles et les clients :
 * l'import repart d'une comptabilite vide, comme sur une base neuve.
 *
 * <p>Les tables de depart ({@link #RACINES}) sont videes entierement. Toute
 * table qui les reference, directement ou non, perd les lignes devenues
 * orphelines (lignes de vente, observations d'une note, pieces jointes d'une
 * note, notifications d'une note...) et garde les autres. Ces tables
 * dependantes sont lues dans le catalogue PostgreSQL : celles d'une migration
 * future suivent d'office.</p>
 *
 * <p>Suppressions ligne a ligne ({@code DELETE}, jamais {@code TRUNCATE}) : les
 * declencheurs de la synchronisation les notent, le serveur en ligne efface
 * donc les memes lignes. Les cles etrangeres sont differables (voir
 * ReplicationSchemaVerifier) : verifiees a la validation, une seule fois.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffacementComptable {

    /** Tables videes entierement, avec le libelle montre a l'analyse (vide : non montre). */
    static final Map<String, String> RACINES = racines();

    /** Compteurs de references des donnees effacees, remis a 1 (NF-2026-000001...). */
    static final List<String> COMPTEURS = List.of("seq_note_frais", "seq_transaction_caisse", "seq_recu_caisse",
        "seq_piece_comptable", "seq_mouvement_stock", "seq_depense_vehicule", "seq_transaction_bancaire",
        "seq_recu_banque", "seq_transaction_mobile_money", "seq_recu_mobile_money", "seq_vente",
        "seq_immobilisation", "seq_provision", "seq_production", "seq_retour_vente");

    /** Cles etrangeres : table, colonnes, table referencee, colonnes referencees. */
    private static final String CLES = """
        select c.conrelid::regclass::text, c.confrelid::regclass::text,
               array(select a.attname::text from unnest(c.conkey) with ordinality k(n, i)
                     join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.n order by k.i),
               array(select a.attname::text from unnest(c.confkey) with ordinality k(n, i)
                     join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.n order by k.i)
        from pg_constraint c
        join pg_namespace s on s.oid = c.connamespace and s.nspname = 'public'
        where c.contype = 'f' and c.conrelid <> c.confrelid
        """;

    private final JdbcTemplate jdbc;

    private record Cle(String table, String parent, String[] colonnes, String[] colonnesParent) { }

    private static Map<String, String> racines() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("pieces_comptables", "piece(s) comptable(s)");
        r.put("grand_livre", "ecriture(s)");
        r.put("notes_frais", "note(s) de frais");
        r.put("transactions_caisse", "operation(s) de caisse");
        r.put("transactions_bancaires", "operation(s) de banque");
        r.put("transactions_mobile_money", "operation(s) de mobile money");
        r.put("ventes", "vente(s)");
        r.put("mouvements_stock", "mouvement(s) de stock");
        r.put("productions", "production(s)");
        r.put("drh_bulletins_paie", "bulletin(s) de paie");
        r.put("immobilisations", "immobilisation(s)");
        r.put("provisions", "provision(s)");
        r.put("releves_bancaires", "releve(s) bancaire(s)");
        r.put("camions_minerai", "camion(s) de minerai");
        r.put("depenses_vehicule", "depense(s) de vehicule");
        r.put("factures_normalisees", "facture(s) normalisee(s)");
        r.put("restaurant_sorties_boissons", "sortie(s) de boissons");
        r.put("restaurant_sorties_plats", "sortie(s) de plats");
        r.put("restaurant_sorties_provisions", "sortie(s) de provisions");
        r.put("restaurant_mouvements_emballage", "");
        r.put("stock_niveaux", "");
        r.put("lots_stock", "");
        return r;
    }

    /**
     * Ce que le remplacement effacera, pour l'analyse : « 12 note(s) de frais »...
     * Les tables vides et celles sans libelle ne sont pas citees.
     */
    public List<String> apercu() {
        Set<String> existantes = tablesExistantes();
        List<String> lignes = new ArrayList<>();
        RACINES.forEach((table, libelle) -> {
            if (libelle.isEmpty() || !existantes.contains(table)) return;
            Long n = jdbc.queryForObject("select count(*) from " + ident(table), Long.class);
            if (n != null && n > 0) lignes.add(n + " " + libelle);
        });
        return lignes;
    }

    /**
     * Efface tout dans la transaction en cours : un import refuse ensuite
     * annule aussi l'effacement.
     *
     * @return nombre de lignes supprimees par table (tables touchees seulement)
     */
    public Map<String, Integer> effacer() {
        Set<String> existantes = tablesExistantes();
        List<Cle> cles = jdbc.query(CLES, (rs, i) -> new Cle(rs.getString(1), rs.getString(2),
            (String[]) rs.getArray(3).getArray(), (String[]) rs.getArray(4).getArray()));

        Set<String> racines = new LinkedHashSet<>(RACINES.keySet());
        racines.retainAll(existantes);
        Set<String> touchees = new LinkedHashSet<>(racines);
        boolean ajout = true;
        while (ajout) {
            ajout = false;
            for (Cle c : cles) {
                if (touchees.contains(c.parent()) && touchees.add(c.table())) ajout = true;
            }
        }

        jdbc.execute("set constraints all deferred");
        Map<String, Integer> bilan = new LinkedHashMap<>();
        for (String table : racines) {
            compter(bilan, table, jdbc.update("delete from " + ident(table)));
        }
        // Orphelins des tables dependantes, jusqu'a ce qu'il n'en reste plus : une
        // ligne effacee peut en rendre orpheline une autre, plus bas dans la chaine.
        boolean encore = true;
        while (encore) {
            encore = false;
            for (Cle c : cles) {
                if (racines.contains(c.table()) || !touchees.contains(c.parent())) continue;
                int n = jdbc.update(suppressionOrphelins(c));
                if (n > 0) {
                    compter(bilan, c.table(), n);
                    encore = true;
                }
            }
        }

        // Les bouteilles vides se comptent par leurs mouvements, effaces.
        if (existantes.contains("restaurant_emballages")) {
            jdbc.update("update restaurant_emballages set bouteilles_vides = 0 where bouteilles_vides <> 0");
        }
        Set<String> sequences = new LinkedHashSet<>(jdbc.queryForList(
            "select sequencename::text from pg_sequences where schemaname = 'public'", String.class));
        for (String s : COMPTEURS) {
            if (sequences.contains(s)) jdbc.execute("alter sequence " + ident(s) + " restart");
        }
        log.warn("Remplacement de la comptabilite : lignes effacees {}", bilan);
        return bilan;
    }

    private static String suppressionOrphelins(Cle c) {
        StringBuilder renseignee = new StringBuilder();
        StringBuilder jointure = new StringBuilder();
        for (int i = 0; i < c.colonnes().length; i++) {
            String col = "t." + ident(c.colonnes()[i]);
            renseignee.append(i == 0 ? "" : " and ").append(col).append(" is not null");
            jointure.append(i == 0 ? "" : " and ").append("p.").append(ident(c.colonnesParent()[i]))
                .append(" = ").append(col);
        }
        return "delete from " + ident(c.table()) + " t where " + renseignee
            + " and not exists (select 1 from " + ident(c.parent()) + " p where " + jointure + ")";
    }

    private static void compter(Map<String, Integer> bilan, String table, int n) {
        if (n > 0) bilan.merge(table, n, Integer::sum);
    }

    private Set<String> tablesExistantes() {
        return new LinkedHashSet<>(jdbc.queryForList(
            "select tablename::text from pg_tables where schemaname = 'public'", String.class));
    }

    private static String ident(String nom) {
        return '"' + nom.replace("\"", "\"\"") + '"';
    }
}

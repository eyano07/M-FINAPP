package com.mbsc.finapp.service.instance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Empreinte de chaque table recopiee (nombre de lignes et hachage de leur contenu, dans l'ordre de la cle primaire) :
 * comparees entre les deux serveurs, elles revelent une copie qui a derive (purge directe en base, migration...), et le
 * serveur local fait alors une resynchronisation complete. Les colonnes que la copie en ligne ecrit elle-meme (voir
 * {@link ReplicationApplicateur}) ne comptent pas.
 */
@Service
public class EmpreintesService {

    /** Colonnes ignorees par table (ecrites aussi par la copie en ligne). */
    static final Map<String, List<String>> IGNOREES = Map.of(
        "users", List.of("version_jetons", "actif", "date_maj"),
        "notifications", List.of("lue"));

    private final JdbcTemplate jdbc;
    private final ReplicationSchema schema;
    private final TransactionTemplate tx;

    public EmpreintesService(JdbcTemplate jdbc, ReplicationSchema schema, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.tx.setReadOnly(true);
    }

    /** Empreinte de chaque table recopiee (table -> « nombre:hachage »). */
    public Map<String, String> calculer() {
        return tx.execute(s -> {
            // Les dates avec fuseau s'ecrivent selon le fuseau de la session : le meme partout pour comparer.
            jdbc.execute("set local time zone 'UTC'");
            Map<String, String> resultat = new LinkedHashMap<>();
            for (ReplicationSchema.Table t : schema.tables().values()) {
                String ligne = "to_jsonb(x)" + IGNOREES.getOrDefault(t.nom(), List.of()).stream()
                    .map(c -> " - '" + c + "'").collect(Collectors.joining());
                String ordre = t.cle().stream().map(c -> "x." + ReplicationSchema.ident(c)).collect(Collectors.joining(", "));
                resultat.put(t.nom(), jdbc.queryForObject("select count(*) || ':' || coalesce(md5(string_agg(md5((" + ligne
                    + ")::text), '' order by " + ordre + ")), '') from public." + ReplicationSchema.ident(t.nom()) + " x",
                    String.class));
            }
            return resultat;
        });
    }

    /** Tables dont l'empreinte differe (ou qui n'existent que d'un cote). */
    public static List<String> differences(Map<String, String> ici, Map<String, String> la) {
        java.util.Set<String> toutes = new java.util.TreeSet<>(ici.keySet());
        toutes.addAll(la.keySet());
        return toutes.stream().filter(t -> !java.util.Objects.equals(ici.get(t), la.get(t))).toList();
    }
}

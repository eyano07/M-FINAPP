package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Instantane complet des tables recopiees : produit par un serveur (dans une seule transaction REPEATABLE READ, donc
 * coherent), applique par l'autre en remplacement complet (dans une seule transaction, cles etrangeres differees).
 * Sert au demarrage du mode local (le serveur en ligne -> le serveur local) et a la resynchronisation complete (le
 * serveur local -> le serveur en ligne).
 *
 * <p>Format : lignes JSON compressees (gzip), une par element — entete (version du schema), puis pour chaque table son
 * nom et ses lignes par paquets de {@value #PAQUET}, puis les sequences, la liste des fichiers cites et la fin.</p>
 */
@Service
public class InstantaneService {

    private static final Logger log = LoggerFactory.getLogger(InstantaneService.class);
    static final int PAQUET = 500;

    /** Bilan d'un instantane applique. */
    public record Bilan(int tables, long lignes, List<String> fichiers) {}

    private final ReplicationSchema schema;
    private final ReplicationApplicateur applicateur;
    private final FichiersReplication fichiers;
    private final InstanceService instance;
    private final ObjectMapper json;
    private final JdbcTemplate lecteur;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txLecture;
    private final TransactionTemplate txEcriture;

    public InstantaneService(ReplicationSchema schema, ReplicationApplicateur applicateur, FichiersReplication fichiers,
                             InstanceService instance, ObjectMapper json, DataSource dataSource, JdbcTemplate jdbc,
                             PlatformTransactionManager tm) {
        this.schema = schema;
        this.applicateur = applicateur;
        this.fichiers = fichiers;
        this.instance = instance;
        this.json = json;
        this.jdbc = jdbc;
        this.lecteur = new JdbcTemplate(dataSource);
        this.lecteur.setFetchSize(PAQUET);   // lecture par paquets (curseur) : une grande table ne charge pas tout en memoire
        this.txLecture = new TransactionTemplate(tm);
        this.txLecture.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txLecture.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.txLecture.setReadOnly(true);
        this.txEcriture = new TransactionTemplate(tm);
        this.txEcriture.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txEcriture.setTimeout(1800);
    }

    /** Ecrit l'instantane complet (gzip) ; ne ferme pas le flux. */
    public void exporter(OutputStream sortie) {
        txLecture.executeWithoutResult(s -> {
            try {
                GZIPOutputStream gz = new GZIPOutputStream(sortie, 64 * 1024);
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8), 64 * 1024);
                ecrireLigne(w, json.createObjectNode().put("type", "entete").put("version", instance.versionSchema())
                    .put("genere", Instant.now().toString()));
                long total = 0;
                for (String table : schema.tables().keySet()) {
                    ecrireLigne(w, json.createObjectNode().put("type", "table").put("nom", table));
                    List<String> paquet = new ArrayList<>(PAQUET);
                    long[] n = {0};
                    lecteur.query("select to_jsonb(x)::text from public." + ReplicationSchema.ident(table) + " x", rs -> {
                        paquet.add(rs.getString(1));
                        n[0]++;
                        if (paquet.size() >= PAQUET) {
                            ecrirePaquet(w, table, paquet);
                            paquet.clear();
                        }
                    });
                    if (!paquet.isEmpty()) ecrirePaquet(w, table, paquet);
                    total += n[0];
                }
                ObjectNode seq = json.createObjectNode().put("type", "sequences");
                ObjectNode valeurs = seq.putObject("valeurs");
                schema.valeursSequences().forEach(valeurs::put);
                ecrireLigne(w, seq);
                ObjectNode f = json.createObjectNode().put("type", "fichiers");
                var chemins = f.putArray("chemins");
                fichiers.tousLesChemins().forEach(chemins::add);
                ecrireLigne(w, f);
                ecrireLigne(w, json.createObjectNode().put("type", "fin").put("tables", schema.tables().size()).put("lignes", total));
                w.flush();
                gz.finish();
                log.info("Instantane produit : {} table(s), {} ligne(s)", schema.tables().size(), total);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void ecrirePaquet(BufferedWriter w, String table, List<String> lignes) {
        try {
            w.write("{\"type\":\"lignes\",\"nom\":");
            w.write(json.writeValueAsString(table));
            w.write(",\"lignes\":[");
            w.write(String.join(",", lignes));
            w.write("]}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ecrireLigne(BufferedWriter w, JsonNode noeud) throws IOException {
        w.write(json.writeValueAsString(noeud));
        w.write('\n');
    }

    /**
     * Applique un instantane (gzip) en remplacement complet des tables recopiees, dans une seule transaction : tout ou
     * rien. Refuse un instantane d'une autre version du schema ou qui ne cite pas exactement les memes tables.
     *
     * @param fusionner vrai pour la resynchronisation d'une copie en ligne (elle garde ce qu'elle ecrit elle-meme : session
     *                  des comptes, activations en attente, notifications lues) ; faux pour l'activation du mode local
     *                  (tout ce qui est ici est remplace)
     */
    public Bilan importer(InputStream entree, boolean fusionner) {
        return txEcriture.execute(s -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(entree, 64 * 1024),
                    StandardCharsets.UTF_8), 64 * 1024);
                applicateur.differerContraintes();
                detacherTablesPropres();
                applicateur.preparerRemplacement();
                String courante = null;
                Set<String> vues = new HashSet<>();
                List<String> chemins = List.of();
                Map<String, Long> sequences = new LinkedHashMap<>();
                boolean fin = false;
                String texte;
                while ((texte = r.readLine()) != null) {
                    if (texte.isBlank()) continue;
                    JsonNode noeud = json.readTree(texte);
                    String type = noeud.path("type").asText();
                    switch (type) {
                        case "entete" -> {
                            int version = noeud.path("version").asInt();
                            if (version != instance.versionSchema()) {
                                throw new IllegalStateException("Instantané d'une autre version (V" + version + ", ce serveur V"
                                    + instance.versionSchema() + ") : mettez les deux serveurs à la même version.");
                            }
                        }
                        case "table" -> {
                            courante = noeud.path("nom").asText();
                            schema.table(courante);
                            vues.add(courante);
                        }
                        case "lignes" -> {
                            if (!noeud.path("nom").asText().equals(courante)) {
                                throw new IllegalStateException("Instantané mal formé (lignes hors de leur table).");
                            }
                            applicateur.transiter(courante, noeud.path("lignes").toString());
                        }
                        case "sequences" -> noeud.path("valeurs").fields()
                            .forEachRemaining(e -> sequences.put(e.getKey(), e.getValue().asLong()));
                        case "fichiers" -> {
                            List<String> liste = new ArrayList<>();
                            noeud.path("chemins").forEach(c -> liste.add(c.asText()));
                            chemins = liste;
                        }
                        case "fin" -> fin = true;
                        default -> throw new IllegalStateException("Instantané mal formé (élément « " + type + " »).");
                    }
                }
                if (!fin) {
                    throw new IllegalStateException("Instantané incomplet (transfert interrompu) : rien n'a été modifié.");
                }
                if (!vues.equals(schema.tables().keySet())) {
                    Set<String> manquantes = new HashSet<>(schema.tables().keySet());
                    manquantes.removeAll(vues);
                    throw new IllegalStateException("Instantané incomplet : tables absentes " + manquantes + ".");
                }
                long lignes = applicateur.remplacerToutes(fusionner);
                applicateur.alignerSequences(sequences);
                log.info("Instantane applique : {} table(s), {} ligne(s), {} fichier(s) cite(s)", vues.size(), lignes, chemins.size());
                return new Bilan(vues.size(), lignes, chemins);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Les tables propres a ce serveur (non recopiees : parametres de l'IA, de la messagerie...) peuvent citer un compte
     * (« modifie par ») que l'instantane va remplacer : la reference est effacee (colonne facultative), sinon la fin de
     * la transaction la refuserait.
     */
    private void detacherTablesPropres() {
        List<Map<String, Object>> liens = jdbc.queryForList(
            "select c.conrelid::regclass::text as tab, a.attname as col from pg_constraint c "
                + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey) "
                + "join pg_namespace n on n.oid = c.connamespace "
                + "where n.nspname = 'public' and c.contype = 'f' and not a.attnotnull "
                + "and c.conrelid::regclass::text in (" + ReplicationSchema.NON_RECOPIEES.stream()
                    .map(t -> "'" + t + "'").collect(java.util.stream.Collectors.joining(", ")) + ")");
        for (Map<String, Object> lien : liens) {
            jdbc.update("update " + lien.get("tab") + " set " + ReplicationSchema.ident((String) lien.get("col")) + " = null");
        }
    }
}

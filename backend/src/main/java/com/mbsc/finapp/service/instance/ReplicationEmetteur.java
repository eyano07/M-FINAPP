package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.PingResponse;
import com.mbsc.finapp.security.CleReplicationFilter;
import com.mbsc.finapp.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Serveur local : envoi au serveur en ligne de tout ce qui a change, toutes les 30 secondes quand il repond (et a la
 * demande : « Synchroniser maintenant »). Sans Internet, rien n'est perdu : le journal s'accumule et part au retour de
 * la connexion. Ordre d'un cycle : version, changements du journal (par lots), fichiers manquants, journal d'audit,
 * validations faites en ligne (voir {@link ActionsDistantesApplicateur}), puis, au premier cycle et toutes les heures,
 * comparaison des empreintes et resynchronisation complete en cas d'ecart.
 */
@Service
public class ReplicationEmetteur {

    private static final Logger log = LoggerFactory.getLogger(ReplicationEmetteur.class);
    private static final int LOT_LIGNES = 500;
    private static final int LOT_OCTETS = 5 * 1024 * 1024;
    private static final Duration DUREE_MAX_CYCLE = Duration.ofMinutes(3);
    private static final Duration DELAI_APPEL = Duration.ofSeconds(60);

    /** Bilan d'un cycle (affiche apres « Synchroniser maintenant »). */
    public record Bilan(boolean ok, String message, int changements, int fichiers, int lignesAudit, int actions, boolean resynchronise) {}

    private final InstanceService instance;
    private final ReplicationClient client;
    private final ReplicationSchema schema;
    private final InstantaneService instantane;
    private final EmpreintesService empreintes;
    private final StorageService storage;
    private final ActionsDistantesApplicateur actions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private volatile Instant dernierControle;

    public ReplicationEmetteur(InstanceService instance, ReplicationClient client, ReplicationSchema schema,
                               InstantaneService instantane, EmpreintesService empreintes, StorageService storage,
                               ActionsDistantesApplicateur actions, JdbcTemplate jdbc, ObjectMapper json) {
        this.instance = instance;
        this.client = client;
        this.schema = schema;
        this.instantane = instantane;
        this.empreintes = empreintes;
        this.storage = storage;
        this.actions = actions;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${app.replication.intervalle-ms:30000}",
               initialDelayString = "${app.replication.delai-initial-ms:20000}")
    public void cycle() {
        if (instance.estLocal()) {
            synchroniser(false);
        }
    }

    /** Un cycle complet ; {@code controleForce} : comparer les empreintes meme si l'heure n'est pas venue. */
    public synchronized Bilan synchroniser(boolean controleForce) {
        if (!instance.estLocal()) {
            return new Bilan(false, "Ce serveur n'est pas en mode local.", 0, 0, 0, 0, false);
        }
        Instant debut = Instant.now();
        int changements = 0;
        int nbFichiers = 0;
        int audit = 0;
        int nbActions = 0;
        boolean resync = false;
        try {
            PingResponse ping = ping();
            if (ping.versionSchema() != instance.versionSchema()) {
                String m = "Versions différentes : serveur en ligne en V" + ping.versionSchema() + ", serveur local en V"
                    + instance.versionSchema() + ". Mettez les deux serveurs à jour avec la même livraison ; rien n'est perdu "
                    + "en attendant.";
                instance.noterErreur(m);
                return new Bilan(false, m, 0, 0, 0, 0, false);
            }
            List<String> manquants = new ArrayList<>();
            while (Duration.between(debut, Instant.now()).compareTo(DUREE_MAX_CYCLE) < 0) {
                Envoi e = envoyerLot();
                if (e.nombre() == 0) break;
                changements += e.nombre();
                manquants.addAll(e.fichiersManquants());
            }
            nbFichiers += envoyerFichiers(manquants);
            audit = envoyerAudit();
            nbActions = actions.traiter();
            if (controleForce || dernierControle == null
                || dernierControle.isBefore(Instant.now().minus(Duration.ofHours(1)))) {
                resync = controlerDerive();
                dernierControle = Instant.now();
            }
            instance.noterSucces();
            String m = "Synchronisé : " + changements + " changement(s), " + nbFichiers + " fichier(s), " + audit
                + " ligne(s) d'audit, " + nbActions + " action(s) faite(s) en ligne" + (resync ? ", resynchronisation complète" : "") + ".";
            return new Bilan(true, m, changements, nbFichiers, audit, nbActions, resync);
        } catch (Revoque e) {
            arreter(e.getMessage());
            return new Bilan(false, e.getMessage(), changements, nbFichiers, audit, nbActions, resync);
        } catch (ReplicationClient.Injoignable e) {
            String m = "Hors connexion : " + e.getMessage() + ". Les changements partiront au retour de la connexion.";
            instance.noterErreur(m);
            return new Bilan(false, m, changements, nbFichiers, audit, nbActions, resync);
        } catch (RuntimeException | IOException e) {
            log.warn("Synchronisation interrompue : {}", e.toString());
            String m = "Synchronisation interrompue : " + e.getMessage();
            instance.noterErreur(m);
            return new Bilan(false, m, changements, nbFichiers, audit, nbActions, resync);
        }
    }

    // ---------------------------------------------------------------------

    private PingResponse ping() throws IOException {
        ReplicationClient.Reponse r = client.envoyer("GET", "/replication/ping", null, null, Duration.ofSeconds(20));
        verifier(r, "contact");
        return json.readValue(r.corps(), PingResponse.class);
    }

    private record Envoi(int nombre, List<String> fichiersManquants) {}

    /** Envoie le prochain lot du journal ; une fois accepte (tout ou rien), le retire du journal. */
    private Envoi envoyerLot() throws IOException {
        List<Long> ids = new ArrayList<>();
        ArrayNode changements = json.createArrayNode();
        long[] taille = {0};
        jdbc.query("select id, nom_table, operation, ligne::text from journal_replication order by id limit " + LOT_LIGNES, rs -> {
            if (taille[0] > LOT_OCTETS) return;
            String ligne = rs.getString(4);
            try {
                ObjectNode c = changements.addObject();
                c.put("id", rs.getLong(1));
                c.put("t", rs.getString(2));
                c.put("op", rs.getString(3));
                c.set("l", json.readTree(ligne));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ids.add(rs.getLong(1));
            taille[0] += ligne.length();
        });
        if (ids.isEmpty()) return new Envoi(0, List.of());
        ObjectNode lot = json.createObjectNode();
        lot.set("changements", changements);
        ObjectNode seq = lot.putObject("sequences");
        schema.valeursSequences().forEach(seq::put);
        ReplicationClient.Reponse r = client.envoyer("POST", "/replication/lot", gzip(json.writeValueAsBytes(lot)),
            "application/gzip", DELAI_APPEL);
        verifier(r, "envoi des changements");
        JsonNode rep = json.readTree(r.corps());
        List<String> manquants = new ArrayList<>();
        rep.path("fichiersManquants").forEach(f -> manquants.add(f.asText()));
        jdbc.update("delete from journal_replication where id in (" + listeIds(ids) + ")");
        return new Envoi(ids.size(), manquants);
    }

    /** Envoie les fichiers que le serveur en ligne n'a pas encore (contenu brut). */
    private int envoyerFichiers(List<String> chemins) throws IOException {
        int n = 0;
        for (String chemin : chemins.stream().distinct().toList()) {
            if (!storage.existe(chemin)) {
                log.warn("Fichier cite mais absent du serveur local, non envoye : {}", chemin);
                continue;
            }
            byte[] contenu;
            try (InputStream in = storage.charger(chemin).getInputStream()) {
                contenu = in.readAllBytes();
            }
            ReplicationClient.Reponse r = client.envoyer("POST",
                "/replication/fichiers?chemin=" + URLEncoder.encode(chemin, StandardCharsets.UTF_8), contenu,
                "application/octet-stream", DELAI_APPEL);
            verifier(r, "envoi du fichier " + chemin);
            n++;
        }
        return n;
    }

    /** Envoie les lignes du journal d'audit mises de cote, puis les retire de la file. */
    private int envoyerAudit() throws IOException {
        int total = 0;
        while (true) {
            List<Long> ids = new ArrayList<>();
            List<String> lignes = new ArrayList<>();
            jdbc.query("select id, ligne from audit_a_envoyer order by id limit 1000", rs -> {
                ids.add(rs.getLong(1));
                lignes.add(rs.getString(2));
            });
            if (ids.isEmpty()) return total;
            ReplicationClient.Reponse r = client.envoyer("POST", "/replication/audit", gzip(json.writeValueAsBytes(lignes)),
                "application/gzip", DELAI_APPEL);
            verifier(r, "envoi du journal d'audit");
            jdbc.update("delete from audit_a_envoyer where id in (" + listeIds(ids) + ")");
            total += ids.size();
            if (ids.size() < 1000) return total;
        }
    }

    /** Compare les empreintes des deux serveurs ; en cas d'ecart, resynchronisation complete. */
    private boolean controlerDerive() throws IOException {
        ReplicationClient.Reponse r = client.envoyer("GET", "/replication/empreintes", null, null, DELAI_APPEL);
        verifier(r, "contrôle des empreintes");
        Map<String, String> la = json.readValue(r.corps(), new TypeReference<Map<String, String>>() {});
        // Le journal doit etre vide : sinon l'ecart n'est que le retard des derniers changements.
        Long enAttente = jdbc.queryForObject("select count(*) from journal_replication", Long.class);
        if (enAttente != null && enAttente > 0) return false;
        List<String> ecarts = EmpreintesService.differences(empreintes.calculer(), la);
        if (ecarts.isEmpty()) return false;
        log.warn("Copie en ligne differente du serveur local sur {} table(s) {} : resynchronisation complete", ecarts.size(), ecarts);
        resynchroniser();
        return true;
    }

    /** Envoie un instantane complet du serveur local, qui remplace les donnees recopiees du serveur en ligne. */
    public synchronized int resynchroniser() throws IOException {
        Path fichier = Files.createTempFile("instantane-", ".json.gz");
        try {
            try (var sortie = Files.newOutputStream(fichier)) {
                instantane.exporter(sortie);
            }
            ReplicationClient.Reponse r = client.envoyer("POST", "/replication/resynchronisation", Files.readAllBytes(fichier),
                "application/gzip", Duration.ofMinutes(15));
            verifier(r, "resynchronisation complète");
            List<String> manquants = new ArrayList<>();
            json.readTree(r.corps()).path("fichiersManquants").forEach(f -> manquants.add(f.asText()));
            return envoyerFichiers(manquants);
        } finally {
            Files.deleteIfExists(fichier);
        }
    }

    /** Refus du serveur en ligne : l'appairage revoque arrete ce serveur ; tout autre refus est une erreur affichee. */
    private void verifier(ReplicationClient.Reponse r, String etape) throws IOException {
        if (r.ok()) return;
        String code = null;
        String message = r.texte();
        try {
            JsonNode n = json.readTree(r.corps());
            code = n.path("code").asText(null);
            if (n.hasNonNull("message")) message = n.get("message").asText();
        } catch (Exception ignore) {
            // corps non JSON (proxy, page d'erreur) : texte brut
        }
        if (CleReplicationFilter.CODE_REVOQUE.equals(code)) {
            throw new Revoque(message);
        }
        if (message != null && message.length() > 300) message = message.substring(0, 300);
        throw new IllegalStateException("Refus du serveur en ligne (" + etape + ", " + r.statut() + ") : " + message);
    }

    /** Le serveur en ligne a repris la main : ce serveur cesse d'etre principal (jamais deux serveurs principaux). */
    private void arreter(String message) {
        log.error("Appairage revoque par le serveur en ligne : ce serveur passe en lecture seule");
        instance.modifier(p -> {
            p.setMode(ModeInstance.LOCAL_ARRETE);
            p.setDerniereErreur(message + " Ce serveur est désormais en lecture seule : utilisez le serveur en ligne.");
            p.setDerniereErreurLe(Instant.now());
        }, "synchronisation");
    }

    /** Liste d'identifiants (nombres lus dans la base) pour une clause « in ». */
    private static String listeIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static byte[] gzip(byte[] octets) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(1024, octets.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(b)) {
                gz.write(octets);
            }
            return b.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** L'appairage a ete revoque par le serveur en ligne. */
    static final class Revoque extends RuntimeException {
        Revoque(String message) {
            super(message);
        }
    }
}

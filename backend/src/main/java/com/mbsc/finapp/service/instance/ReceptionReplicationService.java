package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Serveur en ligne : reception de ce qu'envoie le serveur local appaire (appels signes, voir CleReplicationFilter) —
 * appairage, lots de changements, fichiers, lignes d'audit, resynchronisation complete, fin d'appairage.
 */
@Service
public class ReceptionReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReceptionReplicationService.class);

    /** Bilan d'un lot applique : nombre de changements, fichiers cites encore absents ici (a envoyer). */
    public record ResultatLot(int appliques, List<String> fichiersManquants) {}

    private final InstanceService instance;
    private final ReplicationApplicateur applicateur;
    private final InstantaneService instantane;
    private final StorageService storage;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ReceptionReplicationService(InstanceService instance, ReplicationApplicateur applicateur, InstantaneService instantane,
                                       StorageService storage, AuditService audit, ObjectMapper json, PlatformTransactionManager tm) {
        this.instance = instance;
        this.applicateur = applicateur;
        this.instantane = instantane;
        this.storage = storage;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(600);
    }

    // ---------------------------------------------------------------------
    // Appairage
    // ---------------------------------------------------------------------

    /**
     * Le serveur local demande a devenir le serveur principal : ce serveur passe en copie (MIROIR), ce qui gele aussitot
     * les saisies ; le serveur local telecharge ensuite l'instantane. Accepte aussi une reprise de la meme demarche.
     */
    public void appairer(String serveur) {
        ParametresInstance p = instance.etat();
        if (p.getMode() == ModeInstance.MIROIR) {
            return;   // demarche reprise apres une coupure : deja en copie
        }
        if (p.getMode() != ModeInstance.EN_LIGNE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce serveur n'est pas un serveur en ligne (mode " + p.getMode() + ").");
        }
        String nom = serveur == null || serveur.isBlank() ? "serveur local" : serveur.trim();
        instance.modifier(e -> {
            e.setMode(ModeInstance.MIROIR);
            e.setServeurAssocie(nom.length() > 150 ? nom.substring(0, 150) : nom);
            e.setAppaireLe(Instant.now());
            e.setDerniereSynchro(null);
            e.setDerniereErreur(null);
            e.setDerniereErreurLe(null);
        }, "serveur local");
        tracer("APPAIRAGE", "Le serveur local « " + nom + " » devient le serveur principal : ce serveur passe en copie consultable.");
        log.info("Appairage : ce serveur devient la copie du serveur local {}", nom);
    }

    /** Echec du demarrage du serveur local avant toute synchronisation : ce serveur redevient principal. */
    public void annulerAppairage() {
        ParametresInstance p = instance.etat();
        if (p.getMode() != ModeInstance.MIROIR) return;
        if (p.getDerniereSynchro() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Des données du serveur local ont déjà été reçues : "
                + "l'appairage ne s'annule plus, désactivez le mode local depuis le serveur local.");
        }
        instance.modifier(e -> {
            e.setMode(ModeInstance.EN_LIGNE);
            e.setServeurAssocie(null);
            e.setAppaireLe(null);
        }, "serveur local");
        tracer("APPAIRAGE_ANNULE", "Démarrage du serveur local interrompu : ce serveur redevient le serveur principal.");
    }

    /**
     * Le serveur local renonce au mode local (apres une derniere synchronisation complete) : ce serveur redevient le
     * serveur principal, et l'appairage est revoque (le serveur local ne peut plus rien envoyer).
     */
    public void liberer() {
        exigerMiroir();
        String appairage = instance.etat().getAppairageId();
        instance.modifier(e -> {
            e.setMode(ModeInstance.EN_LIGNE);
            e.setAppairageRevoque(appairage);
            e.setCleChiffree(null);
            e.setCleFin(null);
            e.setAppairageId(null);
            e.setServeurAssocie(null);
        }, "serveur local");
        tracer("APPAIRAGE_LIBERE", "Mode local désactivé par le serveur local : ce serveur redevient le serveur principal.");
    }

    // ---------------------------------------------------------------------
    // Donnees
    // ---------------------------------------------------------------------

    /** Applique un lot du journal du serveur local (tout ou rien) et renvoie les fichiers cites encore absents ici. */
    public ResultatLot appliquerLot(byte[] gzip) {
        exigerMiroir();
        JsonNode lot = lireGzip(gzip);
        Set<String> chemins = new LinkedHashSet<>();
        Integer n = tx.execute(s -> {
            applicateur.differerContraintes();
            int compte = 0;
            for (JsonNode c : lot.path("changements")) {
                String table = c.path("t").asText();
                char op = c.path("op").asText("U").charAt(0);
                JsonNode ligne = c.path("l");
                applicateur.appliquer(table, op, ligne);
                if (op != 'D') {
                    String chemin = FichiersReplication.cheminDe(table, ligne);
                    if (chemin != null) chemins.add(chemin);
                }
                compte++;
            }
            applicateur.alignerSequences(sequences(lot.path("sequences")));
            return compte;
        });
        instance.noterSucces();
        return new ResultatLot(n == null ? 0 : n, manquants(chemins));
    }

    /** Ecrit un fichier envoye par le serveur local (jamais un fichier deja present). */
    public void recevoirFichier(String chemin, InputStream contenu) {
        exigerMiroir();
        storage.ecrireA(chemin, contenu);
    }

    /** Recopie des lignes du journal d'audit du serveur local. */
    public int recevoirAudit(byte[] gzip) {
        exigerMiroir();
        JsonNode lignes = lireGzip(gzip);
        int n = 0;
        for (JsonNode l : lignes) {
            audit.recopierDuServeurLocal(l.asText());
            n++;
        }
        return n;
    }

    /** Resynchronisation complete : l'instantane du serveur local remplace toutes les donnees recopiees ici. */
    public ResultatLot resynchroniser(byte[] gzip) {
        exigerMiroir();
        InstantaneService.Bilan bilan = instantane.importer(new ByteArrayInputStream(gzip), true);
        instance.noterSucces();
        tracer("RESYNCHRONISATION", "Resynchronisation complète depuis le serveur local : " + bilan.tables() + " table(s), "
            + bilan.lignes() + " ligne(s).");
        return new ResultatLot((int) Math.min(Integer.MAX_VALUE, bilan.lignes()), manquants(bilan.fichiers()));
    }

    /** Parmi ces chemins, ceux dont le fichier manque ici. */
    public List<String> manquants(Collection<String> chemins) {
        return chemins.stream().distinct().filter(c -> {
            try {
                return !storage.existe(c);
            } catch (IllegalArgumentException e) {
                return false;   // chemin invalide : ignore
            }
        }).toList();
    }

    private void exigerMiroir() {
        if (instance.mode() != ModeInstance.MIROIR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ce serveur n'est pas la copie d'un serveur local (mode " + instance.mode() + ").");
        }
    }

    private JsonNode lireGzip(byte[] gzip) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Envoi illisible (compression)", e);
        }
    }

    private static Map<String, Long> sequences(JsonNode noeud) {
        Map<String, Long> valeurs = new LinkedHashMap<>();
        noeud.fields().forEachRemaining(e -> valeurs.put(e.getKey(), e.getValue().asLong()));
        return valeurs;
    }

    private void tracer(String operation, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "SYNCHRONISATION");
        m.put("operation", operation);
        m.put("module", "INSTANCE");
        m.put("reussi", true);
        m.put("detail", detail);
        audit.enregistrer(m);
    }
}

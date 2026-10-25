package com.mbsc.finapp.service.instance;

import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.TestConnexionResponse;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Bascule entre serveur en ligne et serveur local (administrateur seul).
 *
 * <ul>
 *   <li><b>Activer le mode local</b> (sur le serveur local) : le serveur en ligne passe en copie (saisies gelees), puis
 *   ce serveur telecharge toutes ses donnees (instantane coherent) et ses fichiers, et devient le serveur principal. Les
 *   donnees de ce serveur sont remplacees ; tout echec avant la bascule laisse ce serveur intact et rend la main au
 *   serveur en ligne. Se deroule en arriere-plan : l'ecran suit la {@link Progression}.</li>
 *   <li><b>Desactiver le mode local</b> (sur le serveur local) : derniere synchronisation complete, puis le serveur en
 *   ligne redevient principal et ce serveur passe en lecture seule.</li>
 *   <li><b>Reprendre la main</b> (sur le serveur en ligne, panne durable du serveur local) : ce serveur redevient
 *   principal, l'appairage est revoque (le serveur local qui le contacterait encore s'arrete).</li>
 * </ul>
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class ActivationModeLocalService {

    private static final Logger log = LoggerFactory.getLogger(ActivationModeLocalService.class);

    /** Avancement de l'activation (en memoire : suivie par l'ecran d'administration). */
    public record Progression(boolean enCours, String etape, int pourcentage, String erreur, boolean termine,
                              Instant debut, Instant fin) {}

    private final InstanceService instance;
    private final InstanceAdminService admin;
    private final ReplicationClient client;
    private final InstantaneService instantane;
    private final StorageService storage;
    private final ReplicationSchemaVerifier verificateur;
    private final ReplicationEmetteur emetteur;
    private final ActionsDistantesService actions;
    private final CurrentUserProvider currentUser;
    private final JdbcTemplate jdbc;

    private volatile Progression progression = new Progression(false, null, 0, null, false, null, null);

    public ActivationModeLocalService(InstanceService instance, InstanceAdminService admin, ReplicationClient client,
                                      InstantaneService instantane, StorageService storage, ReplicationSchemaVerifier verificateur,
                                      ReplicationEmetteur emetteur, ActionsDistantesService actions,
                                      CurrentUserProvider currentUser, JdbcTemplate jdbc) {
        this.instance = instance;
        this.admin = admin;
        this.client = client;
        this.instantane = instantane;
        this.storage = storage;
        this.verificateur = verificateur;
        this.emetteur = emetteur;
        this.actions = actions;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public Progression progression() {
        return progression;
    }

    /** Lance l'activation du mode local (controles faits avant, puis deroulement en arriere-plan). */
    public synchronized Progression activer() {
        if (progression.enCours()) return progression;
        ParametresInstance p = instance.etat();
        // Un ancien serveur local (mode desactive, ou serveur en ligne qui a repris la main) se relie de nouveau avec une
        // nouvelle cle : ses donnees sont remplacees par celles du serveur en ligne, comme a la premiere activation.
        if (p.getMode() != ModeInstance.EN_LIGNE && p.getMode() != ModeInstance.LOCAL_ARRETE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le mode local ne s'active que sur un serveur en fonctionnement "
                + "habituel ou un ancien serveur local (mode actuel : " + p.getMode() + ").");
        }
        if (!verificateur.pret()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La base n'est pas prête pour la synchronisation : "
                + verificateur.probleme());
        }
        TestConnexionResponse test = admin.tester();
        if (!test.ok()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, test.message());
        }
        String auteur = currentUser.requireUser().getEmail();
        progression = new Progression(true, "Préparation", 0, null, false, Instant.now(), null);
        Thread t = new Thread(() -> executer(auteur), "activation-mode-local");
        t.setDaemon(true);
        t.start();
        return progression;
    }

    private void avancer(String etape, int pourcentage) {
        Progression p = progression;
        progression = new Progression(true, etape, pourcentage, null, false, p.debut(), null);
        log.info("Activation du mode local : {} ({} %)", etape, pourcentage);
    }

    private void executer(String auteur) {
        boolean appaire = false;
        boolean importe = false;
        boolean bascule = false;
        try {
            avancer("Le serveur en ligne passe en copie (saisies gelées)", 5);
            String nom = nomDeCeServeur();
            ReplicationClient.Reponse r = client.envoyer("POST", "/replication/appairer",
                ("{\"serveur\":\"" + nom.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8), "application/json",
                Duration.ofSeconds(60));
            exiger(r, "appairage");
            appaire = true;

            avancer("Téléchargement des données du serveur en ligne", 15);
            InstantaneService.Bilan bilan;
            HttpResponse<InputStream> flux = client.ouvrir("/replication/instantane", Duration.ofMinutes(30));
            if (flux.statusCode() != 200) {
                try (InputStream in = flux.body()) {
                    throw new IllegalStateException("Instantané refusé par le serveur en ligne (" + flux.statusCode() + ") : "
                        + new String(in.readNBytes(300), StandardCharsets.UTF_8));
                }
            }
            try (InputStream in = flux.body()) {
                bilan = instantane.importer(in, false);
            }
            importe = true;

            avancer("Récupération des pièces jointes (" + bilan.fichiers().size() + ")", 70);
            int i = 0;
            for (String chemin : bilan.fichiers()) {
                i++;
                if (storage.existe(chemin)) continue;
                HttpResponse<InputStream> f = client.ouvrir("/replication/fichier?chemin="
                    + URLEncoder.encode(chemin, StandardCharsets.UTF_8), Duration.ofMinutes(5));
                try (InputStream in = f.body()) {
                    if (f.statusCode() == 200) {
                        storage.ecrireA(chemin, in);
                    } else {
                        log.warn("Fichier non recupere ({}) : {}", f.statusCode(), chemin);
                    }
                }
                if (i % 20 == 0) avancer("Récupération des pièces jointes (" + i + "/" + bilan.fichiers().size() + ")",
                    70 + (int) (25.0 * i / Math.max(1, bilan.fichiers().size())));
            }

            avancer("Bascule : ce serveur devient le serveur principal", 96);
            jdbc.update("delete from journal_replication");
            instance.modifier(e -> {
                e.setMode(ModeInstance.LOCAL);
                e.setAppaireLe(Instant.now());
                e.setDerniereSynchro(Instant.now());
                e.setDerniereErreur(null);
                e.setDerniereErreurLe(null);
            }, auteur);
            bascule = true;
            ReplicationClient.Reponse fin = client.envoyer("POST", "/replication/demarrage-termine", null, null, Duration.ofSeconds(30));
            if (!fin.ok()) log.warn("Fin de demarrage non confirmee par le serveur en ligne ({})", fin.statut());
            progression = new Progression(false, "Mode local actif : " + bilan.tables() + " table(s), " + bilan.lignes()
                + " ligne(s) et " + bilan.fichiers().size() + " fichier(s) repris du serveur en ligne.", 100, null, true,
                progression.debut(), Instant.now());
            log.info("Mode local actif");
        } catch (Exception e) {
            // Une erreur SQL arrive enveloppee avec toute la requete : seule la cause (message de la base) parle a l'administrateur.
            Throwable cause = e instanceof DataAccessException ? NestedExceptionUtils.getMostSpecificCause(e) : e;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            log.error("Activation du mode local en echec : {}", message, e);
            if (appaire && !bascule) {
                try {
                    client.envoyer("POST", "/replication/annuler-appairage", null, null, Duration.ofSeconds(30));
                } catch (Exception ignore) {
                    // le serveur en ligne reste en copie : « Reprendre la main » y est possible
                }
            }
            String suite;
            if (bascule) {
                suite = "";
            } else if (importe) {
                // L'instantane est deja enregistre : les donnees et les comptes d'ici sont ceux du serveur en ligne.
                suite = " — les données de ce serveur ont déjà été remplacées par celles du serveur en ligne, mais il n'est pas "
                    + "devenu serveur local ; le serveur en ligne redevient principal. Relancez l'activation.";
            } else {
                suite = " — rien n'a changé sur ce serveur ; le serveur en ligne redevient principal.";
            }
            progression = new Progression(false, "Échec", progression.pourcentage(), message + suite, false,
                progression.debut(), Instant.now());
        }
    }

    /** Serveur local : derniere synchronisation complete, puis le serveur en ligne redevient principal. */
    public String desactiver() {
        if (instance.mode() != ModeInstance.LOCAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce serveur n'est pas en mode local.");
        }
        ReplicationEmetteur.Bilan b = emetteur.synchroniser(true);
        if (!b.ok()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dernière synchronisation impossible, mode local maintenu : "
                + b.message());
        }
        Long reste = jdbc.queryForObject("select count(*) from journal_replication", Long.class);
        if (reste != null && reste > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, reste + " changement(s) restent à envoyer : réessayez dans un "
                + "instant (mode local maintenu).");
        }
        try {
            ReplicationClient.Reponse r = client.envoyer("POST", "/replication/liberer", null, null, Duration.ofSeconds(30));
            exiger(r, "fin de l'appairage");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Serveur en ligne injoignable, mode local maintenu : " + e.getMessage());
        }
        String auteur = currentUser.requireUser().getEmail();
        instance.modifier(e -> {
            e.setMode(ModeInstance.LOCAL_ARRETE);
            e.setDerniereErreur(null);
        }, auteur);
        return "Mode local désactivé : le serveur en ligne redevient le serveur principal ; ce serveur passe en lecture seule.";
    }

    /** Serveur en ligne (copie) : reprend la main, l'appairage est revoque. */
    public String reprendreLaMain() {
        if (instance.mode() != ModeInstance.MIROIR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce serveur n'est pas la copie d'un serveur local.");
        }
        String auteur = currentUser.requireUser().getEmail();
        String appairage = instance.etat().getAppairageId();
        int abandonnees = actions.abandonnerEnAttente();
        instance.modifier(e -> {
            e.setMode(ModeInstance.EN_LIGNE);
            e.setAppairageRevoque(appairage);
            e.setCleChiffree(null);
            e.setCleFin(null);
            e.setAppairageId(null);
            e.setServeurAssocie(null);
        }, auteur);
        log.warn("Reprise de la main : ce serveur redevient principal ({} action(s) en attente abandonnee(s))", abandonnees);
        return "Ce serveur redevient le serveur principal. Le serveur local, s'il se reconnecte, passera en lecture seule."
            + (abandonnees > 0 ? " " + abandonnees + " action(s) faite(s) en ligne et non encore appliquée(s) sont à refaire." : "");
    }

    private static void exiger(ReplicationClient.Reponse r, String etape) {
        if (!r.ok()) {
            String t = r.texte();
            throw new IllegalStateException("Refus du serveur en ligne (" + etape + ", " + r.statut() + ") : "
                + (t.length() > 300 ? t.substring(0, 300) : t));
        }
    }

    private static String nomDeCeServeur() {
        try {
            return "serveur local " + InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "serveur local";
        }
    }
}

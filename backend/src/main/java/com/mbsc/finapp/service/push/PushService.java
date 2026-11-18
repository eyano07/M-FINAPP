package com.mbsc.finapp.service.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.mbsc.finapp.domain.AppareilPush;
import com.mbsc.finapp.domain.Notification;
import com.mbsc.finapp.repository.AppareilPushRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Notifications push de l'application Android (17/11/2026), par Firebase Cloud Messaging (API HTTP v1).
 *
 * <p>Chaque notification de l'application qui mène à une note de frais (à vérifier, à approuver, approuvée, rejetée…)
 * est aussi envoyée aux téléphones du destinataire, après la validation de la transaction et en arrière-plan : une panne
 * de Firebase ou d'Internet ne bloque jamais l'action métier.</p>
 *
 * <p>Le service s'active seul quand la clé du compte de service Firebase est présente
 * ({@code app.push.compte-service}, fichier JSON hors du dépôt) ; sans elle, les téléphones s'inscrivent quand même et
 * rien n'est envoyé. Les messages sont des « données » (titre, texte, lien) : c'est l'application qui affiche la
 * notification et ouvre la note au toucher.</p>
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final String PORTEE = "https://www.googleapis.com/auth/firebase.messaging";

    private final AppareilPushRepository appareils;
    private final UserRepository utilisateurs;
    private final CurrentUserProvider utilisateurCourant;
    private final ObjectMapper json;
    private final String cheminCompteService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executeur = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifications-push");
        t.setDaemon(true);
        return t;
    });

    private boolean initialise;
    private GoogleCredentials identifiants;
    private String projet;

    public PushService(AppareilPushRepository appareils, UserRepository utilisateurs, CurrentUserProvider utilisateurCourant,
                       ObjectMapper json, @Value("${app.push.compte-service:}") String cheminCompteService) {
        this.appareils = appareils;
        this.utilisateurs = utilisateurs;
        this.utilisateurCourant = utilisateurCourant;
        this.json = json;
        this.cheminCompteService = cheminCompteService;
    }

    @PreDestroy
    void arreter() {
        executeur.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Inscription des téléphones
    // ---------------------------------------------------------------------

    /** Inscrit le téléphone au compte connecté ; un jeton déjà connu passe à ce compte (téléphone prêté ou partagé). */
    @Transactional
    public void inscrire(String jeton) {
        Long utilisateurId = utilisateurCourant.requireUserId();
        String j = jeton.strip();
        AppareilPush appareil = appareils.findByJeton(j)
            .orElseGet(() -> AppareilPush.builder().jeton(j).build());
        appareil.setUtilisateur(utilisateurs.getReferenceById(utilisateurId));
        appareil.setDateVue(Instant.now());
        appareils.save(appareil);
    }

    /** Désinscrit le téléphone (déconnexion) : seulement s'il appartient au compte connecté. */
    @Transactional
    public void desinscrire(String jeton) {
        appareils.supprimerJetonDe(jeton.strip(), utilisateurCourant.requireUserId());
    }

    // ---------------------------------------------------------------------
    // Envoi
    // ---------------------------------------------------------------------

    /** Les notifications portées sur les téléphones : celles qui mènent à une note de frais. */
    static boolean concerneLeTelephone(String lien) {
        return lien != null && (lien.equals("/notes-frais") || lien.startsWith("/notes-frais/") || lien.startsWith("/notes-frais?"));
    }

    /** Envoie la notification aux téléphones du destinataire une fois la transaction validée (rien si elle échoue). */
    public void envoyerApresCommit(Notification notification) {
        if (notification.getDestinataire() == null || !concerneLeTelephone(notification.getLien())) return;
        // Instantané : la notification et son destinataire (lazy) ne se lisent pas hors transaction.
        Long destinataire = notification.getDestinataire().getId();
        String titre = notification.getTitre();
        String message = notification.getMessage();
        String lien = notification.getLien();
        String id = notification.getId() == null ? "" : notification.getId().toString();
        Runnable envoi = () -> executeur.execute(() -> envoyer(destinataire, titre, message, lien, id));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    envoi.run();
                }
            });
        } else {
            envoi.run();
        }
    }

    /**
     * Widget Trésorerie : message silencieux (aucune notification affichée) aux téléphones de ces comptes, qui
     * relisent alors leur résumé. Les messages non encore remis se remplacent (collapse_key) et expirent en 10 min.
     */
    public void rafraichirWidgets(java.util.Collection<Long> comptes) {
        // Après la validation de la transaction en cours : le téléphone doit relire l'état enregistré.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            java.util.List<Long> copie = java.util.List.copyOf(comptes);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    envoyerRafraichissement(copie);
                }
            });
        } else {
            envoyerRafraichissement(comptes);
        }
    }

    private void envoyerRafraichissement(java.util.Collection<Long> comptes) {
        executeur.execute(() -> {
            try {
                if (!pret()) return;
                identifiants.refreshIfExpired();
                String acces = identifiants.getAccessToken().getTokenValue();
                for (Long compte : comptes) {
                    for (String jeton : appareils.jetonsDe(compte)) envoyerSilencieux(acces, jeton);
                }
            } catch (Exception e) {
                log.warn("Mise à jour des widgets non envoyée : {}", e.getMessage());
            }
        });
    }

    private void envoyerSilencieux(String acces, String jeton) throws Exception {
        ObjectNode corps = json.createObjectNode();
        ObjectNode msg = corps.putObject("message");
        msg.put("token", jeton);
        msg.putObject("data").put("type", "widget");
        ObjectNode android = msg.putObject("android");
        android.put("priority", "NORMAL");
        android.put("ttl", "600s");
        android.put("collapse_key", "widget-tresorerie");
        HttpRequest requete = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projet + "/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + acces)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corps)))
            .build();
        HttpResponse<String> reponse = http.send(requete, HttpResponse.BodyHandlers.ofString());
        if (reponse.statusCode() / 100 == 2) return;
        if (jetonPerime(reponse.statusCode(), reponse.body())) appareils.supprimerJeton(jeton);
        else log.warn("Firebase a refusé la mise à jour du widget ({}) : {}", reponse.statusCode(), resume(reponse.body()));
    }

    private void envoyer(Long destinataire, String titre, String message, String lien, String id) {
        try {
            if (!pret()) return;
            List<String> jetons = appareils.jetonsDe(destinataire);
            if (jetons.isEmpty()) return;
            identifiants.refreshIfExpired();
            String acces = identifiants.getAccessToken().getTokenValue();
            for (String jeton : jetons) {
                envoyerA(acces, jeton, titre, message, lien, id);
            }
        } catch (Exception e) {
            log.warn("Notification push non envoyée : {}", e.getMessage());
        }
    }

    private void envoyerA(String acces, String jeton, String titre, String message, String lien, String id) throws Exception {
        ObjectNode corps = json.createObjectNode();
        ObjectNode msg = corps.putObject("message");
        msg.put("token", jeton);
        ObjectNode donnees = msg.putObject("data");
        donnees.put("titre", titre == null ? "" : titre);
        donnees.put("message", message == null ? "" : message);
        donnees.put("lien", lien == null ? "" : lien);
        donnees.put("id", id);
        ObjectNode android = msg.putObject("android");
        android.put("priority", "HIGH");
        android.put("ttl", "86400s");

        HttpRequest requete = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projet + "/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + acces)
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corps)))
            .build();
        HttpResponse<String> reponse = http.send(requete, HttpResponse.BodyHandlers.ofString());
        if (reponse.statusCode() / 100 == 2) return;
        if (jetonPerime(reponse.statusCode(), reponse.body())) {
            appareils.supprimerJeton(jeton);
            log.info("Téléphone retiré des notifications push : jeton périmé (application désinstallée ou réinstallée).");
        } else {
            log.warn("Firebase a refusé la notification push ({}) : {}", reponse.statusCode(), resume(reponse.body()));
        }
    }

    /** Jeton que Firebase ne connaît plus : 404 UNREGISTERED, ou 400 sur un jeton mal formé. */
    static boolean jetonPerime(int statut, String corps) {
        if (statut == 404) return true;
        return statut == 400 && corps != null && (corps.contains("UNREGISTERED") || corps.contains("registration token"));
    }

    private static String resume(String corps) {
        if (corps == null) return "";
        String c = corps.replaceAll("\\s+", " ");
        return c.length() > 300 ? c.substring(0, 300) + "…" : c;
    }

    /** Charge la clé du compte de service au premier envoi ; sans clé, le service reste inactif (journalisé une fois). */
    private synchronized boolean pret() {
        if (!initialise) {
            initialise = true;
            if (!StringUtils.hasText(cheminCompteService) || !Files.isReadable(Path.of(cheminCompteService))) {
                log.info("Notifications push inactives : pas de clé de compte de service Firebase (app.push.compte-service).");
                return false;
            }
            try {
                Path chemin = Path.of(cheminCompteService);
                JsonNode cle = json.readTree(chemin.toFile());
                projet = cle.path("project_id").asText("");
                try (InputStream entree = Files.newInputStream(chemin)) {
                    identifiants = GoogleCredentials.fromStream(entree).createScoped(List.of(PORTEE));
                }
                if (projet.isEmpty()) {
                    identifiants = null;
                    log.warn("Notifications push inactives : la clé Firebase ne précise pas de project_id.");
                } else {
                    log.info("Notifications push actives (projet Firebase {}).", projet);
                }
            } catch (Exception e) {
                identifiants = null;
                log.warn("Notifications push inactives : clé Firebase illisible ({}).", e.getMessage());
            }
        }
        return identifiants != null;
    }
}

package com.mbsc.desktop.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mbsc.desktop.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client REST minimal vers l'API Spring Boot (JDK HttpClient).
 * Gere l'authentification JWT et l'appel de synchronisation par lot.
 */
public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build();

    private String accessToken;

    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    public boolean isAuthenticated() {
        return accessToken != null && !accessToken.isBlank();
    }

    /** Verifie la disponibilite du backend (endpoint public /health). */
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.API_BASE_URL + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Ping backend echoue : {}", e.getMessage());
            return false;
        }
    }

    /** Authentifie le caissier et stocke le jeton d'acces. */
    public AuthDtos.AuthResponse login(String email, String motDePasse) throws Exception {
        var body = mapper.writeValueAsString(new AuthDtos.LoginRequest(email, motDePasse));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(AppConfig.API_BASE_URL + "/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("Echec d'authentification pour {} (HTTP {})", email, resp.statusCode());
            throw new ApiException("Echec d'authentification (" + resp.statusCode() + ")");
        }
        AuthDtos.AuthResponse auth =
            mapper.readValue(resp.body(), AuthDtos.AuthResponse.class);
        setAccessToken(auth.accessToken());
        log.info("Caissier authentifie : {}", email);
        return auth;
    }

    /** Pousse un lot de transactions de maniere idempotente. */
    public SyncDtos.SyncBatchResponse pushBatch(SyncDtos.SyncBatchRequest request) throws Exception {
        var body = mapper.writeValueAsString(request);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(AppConfig.API_BASE_URL + "/sync/batch"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            throw new ApiException("Jeton expire ou invalide");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException("Erreur serveur (" + resp.statusCode() + ")");
        }
        return mapper.readValue(resp.body(), SyncDtos.SyncBatchResponse.class);
    }

    /**
     * Recupere les notes de frais transmises a la caisse (statut TRANSMISE_CAISSE),
     * triees par priorite decroissante puis par anciennete cote backend.
     */
    public List<NoteDtos.NoteAPayer> listerNotesAPayer() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(AppConfig.API_BASE_URL + "/notes-frais?statut=TRANSMISE_CAISSE"))
            .header("Authorization", "Bearer " + accessToken)
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            throw new ApiException("Jeton expire ou invalide");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException("Erreur serveur (" + resp.statusCode() + ")");
        }
        NoteDtos.NoteAPayer[] notes =
            mapper.readValue(resp.body(), NoteDtos.NoteAPayer[].class);
        return List.of(notes);
    }

    /**
     * Retourne le solde du compte caisse 571 en CDF (depuis la balance backend).
     * Retourne null si non accessible (role sans acces ou erreur).
     */
    public java.math.BigDecimal getSolde571() {
        if (!isAuthenticated()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.API_BASE_URL + "/caisse/balance"))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            // Trouver le compte 571 dans la liste
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(resp.body());
            for (com.fasterxml.jackson.databind.JsonNode ligne : arr) {
                if ("571".equals(ligne.path("compteNumero").asText())) {
                    String solde = ligne.path("solde").asText(null);
                    if (solde != null) return new java.math.BigDecimal(solde);
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("getSolde571 echoue : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Retourne le taux de change actuel (1 USD = X FC).
     * Retourne null si non accessible.
     */
    public java.math.BigDecimal getTauxChange() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.API_BASE_URL + "/admin/taux-change"))
                .header("Authorization", "Bearer " + (accessToken != null ? accessToken : ""))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(resp.body());
            String taux = node.path("taux").asText(null);
            if (taux != null) return new java.math.BigDecimal(taux);
            return null;
        } catch (Exception e) {
            log.debug("getTauxChange echoue : {}", e.getMessage());
            return null;
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}

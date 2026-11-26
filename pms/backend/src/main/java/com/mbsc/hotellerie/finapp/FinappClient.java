package com.mbsc.hotellerie.finapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.hotellerie.appairage.AppairageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Appels vers M-FINAPP. Les routes {@code /pms/**} sont signées avec la clé d'appairage ; celles qui agissent au nom
 * d'un utilisateur portent en plus son jeton d'accès M-FINAPP (les droits, l'audit et la séparation des tâches restent
 * nominatifs). Les routes d'authentification ({@code /auth/**}) sont les routes ordinaires de M-FINAPP.
 */
@Component
public class FinappClient {

    private static final Logger log = LoggerFactory.getLogger(FinappClient.class);

    private final String base;
    private final AppairageService appairage;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public FinappClient(@Value("${app.finapp-url}") String base, AppairageService appairage, ObjectMapper json) {
        this.base = base.replaceAll("/+$", "") + "/api";
        this.appairage = appairage;
        this.json = json;
    }

    /** Appel signé de M-FINAPP. {@code jeton} : jeton d'accès de l'utilisateur, null pour une route système. */
    public JsonNode signe(String methode, String chemin, Object corps, String jeton) {
        Signature.Cle cle = appairage.cle().orElseThrow(() -> new FinappException(409,
            "L'hôtellerie n'est pas encore appairée avec M-FINAPP : collez la clé d'appairage dans l'écran de premier lancement."));
        byte[] octets = corps == null ? new byte[0] : ecrire(corps);
        long horodatage = System.currentTimeMillis();
        String nonce = Signature.nonce();
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + chemin))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("X-Pms-Appairage", cle.appairageId())
            .header("X-Pms-Horodatage", Long.toString(horodatage))
            .header("X-Pms-Nonce", nonce)
            .header("X-Pms-Signature", Signature.signer(cle.secret(), cle.appairageId(), horodatage, nonce, methode, chemin, octets));
        if (jeton != null) r.header("Authorization", "Bearer " + jeton);
        if (corps != null) r.header("Content-Type", "application/json");
        r.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(octets));
        return envoyer(r.build());
    }

    /** Appel ordinaire (non signé) de M-FINAPP au nom de l'utilisateur (son propre compte : mot de passe). */
    public JsonNode auNomDe(String methode, String chemin, Object corps, String jeton) {
        byte[] octets = corps == null ? new byte[0] : ecrire(corps);
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + chemin))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + jeton);
        if (corps != null) r.header("Content-Type", "application/json");
        r.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(octets));
        return envoyer(r.build());
    }

    /** Appel ordinaire (non signé) d'une route d'authentification de M-FINAPP. */
    public JsonNode simple(String methode, String chemin, Object corps) {
        byte[] octets = corps == null ? new byte[0] : ecrire(corps);
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + chemin))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
        if (corps != null) r.header("Content-Type", "application/json");
        r.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(octets));
        return envoyer(r.build());
    }

    private JsonNode envoyer(HttpRequest requete) {
        HttpResponse<String> reponse;
        try {
            reponse = http.send(requete, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("M-FINAPP injoignable ({}) : {}", requete.uri().getPath(), e.getMessage());
            throw new FinappException(0, "M-FINAPP est injoignable pour le moment : réessayez dans un instant.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FinappException(0, "Appel à M-FINAPP interrompu.");
        }
        int statut = reponse.statusCode();
        JsonNode corps = lire(reponse.body());
        if (statut >= 200 && statut < 300) return corps;
        String message = corps != null && corps.hasNonNull("message") ? corps.get("message").asText() : null;
        if (statut == 401 && message == null) message = "Session M-FINAPP expirée : reconnectez-vous.";
        if (statut == 403 && message == null) message = "M-FINAPP refuse : vos droits ne permettent pas cette action.";
        if (message == null) message = "M-FINAPP a répondu par une erreur (" + statut + ").";
        throw new FinappException(statut, message, corps != null && corps.hasNonNull("code") ? corps.get("code").asText() : null);
    }

    private byte[] ecrire(Object corps) {
        try {
            return json.writeValueAsBytes(corps);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode lire(String texte) {
        if (texte == null || texte.isBlank()) return null;
        try {
            return json.readTree(texte);
        } catch (Exception e) {
            return null;
        }
    }
}

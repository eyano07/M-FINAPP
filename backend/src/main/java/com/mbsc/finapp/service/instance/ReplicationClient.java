package com.mbsc.finapp.service.instance;

import com.mbsc.finapp.domain.ParametresInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Appels du serveur local vers le serveur en ligne (toujours a l'initiative du local : aucune ouverture de port vers le
 * reseau de l'entreprise). Chaque appel est signe avec la cle d'appairage (voir {@link SignatureReplication}).
 */
@Component
public class ReplicationClient {

    /** Reponse brute d'un appel. */
    public record Reponse(int statut, byte[] corps) {
        public boolean ok() {
            return statut >= 200 && statut < 300;
        }

        public String texte() {
            return corps == null ? "" : new String(corps, StandardCharsets.UTF_8);
        }
    }

    /** Erreur de transport (serveur injoignable, delai depasse) : la synchronisation reprendra au cycle suivant. */
    public static class Injoignable extends IOException {
        public Injoignable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final InstanceService instance;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public ReplicationClient(InstanceService instance) {
        this.instance = instance;
    }

    /**
     * Adresse de l'API du serveur en ligne a partir de ce que l'administrateur a saisi : schema ajoute s'il manque
     * (http pour une adresse IP ou un port explicite, https sinon), barre finale et « /api » retires.
     */
    public static String normaliserAdresse(String saisie) {
        if (!StringUtils.hasText(saisie)) {
            throw new IllegalArgumentException("Saisissez l'adresse du serveur en ligne (ex. https://erp.exemple.com ou http://192.0.2.10:9090).");
        }
        String a = saisie.trim();
        if (!a.matches("(?i)^https?://.*")) {
            boolean ipOuPort = a.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$") || a.matches("^[^/]+:\\d+(/.*)?$");
            a = (ipOuPort ? "http://" : "https://") + a;
        }
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.toLowerCase().endsWith("/api")) a = a.substring(0, a.length() - 4);
        try {
            URI u = URI.create(a);
            if (u.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Adresse du serveur en ligne invalide : « " + saisie.trim() + " ».");
        }
        return a;
    }

    private String base() {
        ParametresInstance p = instance.etat();
        if (!StringUtils.hasText(p.getAdresseDistante())) {
            throw new IllegalStateException("Adresse du serveur en ligne non renseignée.");
        }
        return p.getAdresseDistante() + "/api";
    }

    private HttpRequest.Builder requete(String methode, String chemin, byte[] corps, Duration delai) {
        SignatureReplication.Cle cle = instance.cle()
            .orElseThrow(() -> new IllegalStateException("Clé d'appairage absente ou illisible : ressaisissez-la."));
        long horodatage = System.currentTimeMillis();
        String nonce = SignatureReplication.nouveauNonce();
        String signature = SignatureReplication.signer(cle.secret(), cle.appairageId(), horodatage, nonce, methode, chemin, corps);
        return HttpRequest.newBuilder(URI.create(base() + chemin))
            .timeout(delai)
            .header(SignatureReplication.EN_TETE_APPAIRAGE, cle.appairageId())
            .header(SignatureReplication.EN_TETE_HORODATAGE, Long.toString(horodatage))
            .header(SignatureReplication.EN_TETE_NONCE, nonce)
            .header(SignatureReplication.EN_TETE_SIGNATURE, signature)
            .header(SignatureReplication.EN_TETE_VERSION, Integer.toString(instance.versionSchema()));
    }

    /** Appel avec un corps (ou sans : {@code corps} null) ; la reponse est lue en entier. */
    public Reponse envoyer(String methode, String chemin, byte[] corps, String typeContenu, Duration delai) throws Injoignable {
        byte[] octets = corps == null ? new byte[0] : corps;
        HttpRequest.Builder b = requete(methode, chemin, octets, delai);
        if (typeContenu != null) b.header("Content-Type", typeContenu);
        b.method(methode, octets.length == 0 && "GET".equals(methode)
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(octets));
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reponse(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new Injoignable("Serveur en ligne injoignable : " + messageCourt(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Injoignable("Appel interrompu", e);
        }
    }

    /** GET dont la reponse est lue en flux (instantane, fichiers) ; l'appelant ferme le flux. */
    public HttpResponse<InputStream> ouvrir(String chemin, Duration delai) throws Injoignable {
        HttpRequest r = requete("GET", chemin, new byte[0], delai).GET().build();
        try {
            return http.send(r, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new Injoignable("Serveur en ligne injoignable : " + messageCourt(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Injoignable("Appel interrompu", e);
        }
    }

    private static String messageCourt(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}

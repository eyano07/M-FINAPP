package com.mbsc.finapp.service.emcf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Appel HTTP du e-MCF (mode TEST ou PRODUCTION). Le chemin des requêtes ({@value #CHEMIN_FACTURE}) et l'en-tête
 * d'authentification sont provisoires : à confirmer avec la spécification officielle de la DGI.
 */
@Component
public class EmcfHttpClient implements EmcfClient {

    static final String CHEMIN_FACTURE = "/invoice";
    static final String CHEMIN_STATUT = "/status";

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public EmcfHttpClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public EmcfReponse certifier(ConfigEmcf config, EmcfDemande demande, String json) {
        HttpResponse<String> r = envoyer(config, HttpRequest.newBuilder(uri(config, CHEMIN_FACTURE))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
        return EmcfJson.depuisJson(mapper, r.body());
    }

    @Override
    public String tester(ConfigEmcf config) {
        HttpResponse<String> r = envoyer(config, HttpRequest.newBuilder(uri(config, CHEMIN_STATUT)).GET());
        return "Le dispositif répond (HTTP " + r.statusCode() + ").";
    }

    private HttpResponse<String> envoyer(ConfigEmcf c, HttpRequest.Builder b) {
        if (!StringUtils.hasText(c.urlBase())) throw new ErreurEmcf("Adresse du e-MCF non renseignée.", false, null);
        b.timeout(Duration.ofMillis(Math.max(c.delaiMs(), 1000)));
        if (StringUtils.hasText(c.jeton())) b.header("Authorization", "Bearer " + c.jeton());
        b.header("Accept", "application/json");
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErreurEmcf("Appel du dispositif interrompu.", true, e);
        } catch (IOException e) {
            throw new ErreurEmcf("Dispositif injoignable : " + e.getMessage(), true, e);
        }
        int code = r.statusCode();
        if (code >= 500 || code == 408 || code == 429) {
            throw new ErreurEmcf("Dispositif indisponible (HTTP " + code + ").", true, null);
        }
        if (code == 401 || code == 403) {
            throw new ErreurEmcf("Accès refusé par le dispositif (HTTP " + code + ") : vérifiez le jeton.", true, null);
        }
        if (code >= 400) {
            String corps = r.body() == null ? "" : r.body();
            throw new ErreurEmcf("Facture refusée par le dispositif (HTTP " + code + ") : "
                + (corps.length() > 300 ? corps.substring(0, 300) : corps), false, null);
        }
        return r;
    }

    private static URI uri(ConfigEmcf c, String chemin) {
        return URI.create(c.urlBase().strip().replaceAll("/+$", "") + chemin);
    }
}

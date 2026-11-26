package com.mbsc.hotellerie.canal;

import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.reservation.Durees;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Lecture d'un calendrier iCal publié par un canal (http ou https seulement, 2 Mo au plus, 20 s au plus). */
@Component
public class AdaptateurIcal implements AdaptateurCanal {

    private static final int TAILLE_MAX = 2 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public List<Ical.Evenement> lire(String url) {
        URI uri = verifier(url);
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "M-FINAPP-Hotellerie/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new Refus(502, "Le canal répond par le code " + r.statusCode() + ".");
            try (InputStream in = r.body()) {
                byte[] octets = in.readNBytes(TAILLE_MAX + 1);
                if (octets.length > TAILLE_MAX) throw new Refus(502, "Calendrier trop volumineux (plus de 2 Mo).");
                String texte = new String(octets, StandardCharsets.UTF_8);
                if (!texte.contains("BEGIN:VCALENDAR")) throw new Refus(502, "L'adresse ne renvoie pas un calendrier iCal.");
                return Ical.lire(texte, Durees.FUSEAU);
            }
        } catch (IOException e) {
            throw new Refus(502, "Canal injoignable : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refus(502, "Lecture interrompue.");
        }
    }

    static URI verifier(String url) {
        try {
            URI u = URI.create(url.strip());
            if (u.getScheme() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http")) || u.getHost() == null) {
                throw new IllegalArgumentException();
            }
            String hote = u.getHost().toLowerCase();
            // Jamais un service interne (conteneurs, poste local) : un calendrier de canal est sur Internet.
            if (!hote.contains(".") || hote.equals("localhost") || hote.startsWith("127.") || hote.startsWith("169.254.")
                || hote.equals("0.0.0.0") || hote.startsWith("[")) {
                throw new IllegalArgumentException();
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw Refus.invalide("Adresse du calendrier invalide : collez l'adresse iCal (https://…) fournie par le canal.");
        }
    }
}

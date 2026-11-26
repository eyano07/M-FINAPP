package com.mbsc.finapp.service.hotellerie;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.service.ia.ChiffrementSecretService;
import com.mbsc.finapp.service.instance.SignatureReplication;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Connexion de M-FINAPP à l'application Hôtellerie (PMS, V152) : adresse et port du PMS, clé d'appairage et codes de
 * connexion unique. Une seule ligne ({@code integration_pms}, id = 1).
 *
 * <p>La clé d'appairage a la forme de celle de la réplication ({@link SignatureReplication} : {@code MBSC1.<id>.<secret>}).
 * Elle est générée ici, affichée une seule fois à l'administrateur qui la recopie dans le PMS, et conservée chiffrée
 * ({@link ChiffrementSecretService}). Chaque appel du PMS est signé avec elle (HMAC-SHA256, horodatage, nonce).</p>
 */
@Service
@RequiredArgsConstructor
public class IntegrationPmsService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationPmsService.class);
    private static final long ID = 1L;
    /** Durée de validité d'un code de connexion unique. */
    static final Duration VALIDITE_CODE = Duration.ofSeconds(60);
    /** Adresse : IP ou nom d'hôte, avec éventuellement http:// ou https:// devant. */
    private static final Pattern ADRESSE = Pattern.compile("^(https?://)?[A-Za-z0-9]([A-Za-z0-9.-]{0,253})$");
    private static final SecureRandom HASARD = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ChiffrementSecretService chiffrement;

    /** État de la connexion, pour l'écran d'administration (jamais la clé elle-même). */
    public record Etat(String adresse, Integer port, String url, boolean cleDefinie, String cleFin, Instant cleCreeLe,
                       Instant dernierContact) { }

    @Transactional(readOnly = true)
    public Etat etat() {
        try {
            return jdbc.queryForObject("""
                select adresse, port, cle_chiffree, cle_creee_le, dernier_contact from integration_pms where id = ?
                """, (rs, i) -> {
                String adresse = rs.getString("adresse");
                Integer port = (Integer) rs.getObject("port");
                String chiffree = rs.getString("cle_chiffree");
                String fin = null;
                if (chiffree != null) {
                    try {
                        fin = SignatureReplication.lire(chiffrement.dechiffrer(chiffree)).fin();
                    } catch (RuntimeException e) {
                        log.warn("Clé d'appairage du PMS illisible : {}", e.getMessage());
                    }
                }
                return new Etat(adresse, port, url(adresse, port), chiffree != null, fin, instant(rs.getTimestamp("cle_creee_le")),
                    instant(rs.getTimestamp("dernier_contact")));
            }, ID);
        } catch (EmptyResultDataAccessException e) {
            return new Etat(null, null, null, false, null, null, null);
        }
    }

    /** Enregistre l'adresse et le port du PMS. */
    @Transactional
    public Etat definirAdresse(String adresse, Integer port, User auteur) {
        String propre = adresse == null ? "" : adresse.strip().replaceAll("/+$", "");
        if (!ADRESSE.matcher(propre).matches()) {
            throw new IllegalArgumentException("Adresse invalide : saisissez l'adresse IP ou le nom du serveur de l'hôtellerie "
                + "(ex. 192.0.2.10), sans chemin.");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port invalide : un nombre entre 1 et 65535 (ex. 8090).");
        }
        assurerLigne();
        jdbc.update("update integration_pms set adresse = ?, port = ?, maj_le = now(), maj_par = ? where id = ?",
            propre, port, auteur.getEmail(), ID);
        return etat();
    }

    /** Nouvelle clé d'appairage : remplace l'ancienne (le PMS doit recevoir la nouvelle). Renvoyée une seule fois. */
    @Transactional
    public String nouvelleCle(User auteur) {
        SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
        assurerLigne();
        jdbc.update("update integration_pms set cle_chiffree = ?, cle_creee_le = now(), maj_le = now(), maj_par = ? where id = ?",
            chiffrement.chiffrer(cle.texte()), auteur.getEmail(), ID);
        log.info("Nouvelle clé d'appairage du PMS générée par {}", auteur.getEmail());
        return cle.texte();
    }

    /** Clé d'appairage déchiffrée, si elle est définie. */
    @Transactional(readOnly = true)
    public Optional<SignatureReplication.Cle> cle() {
        try {
            String chiffree = jdbc.queryForObject("select cle_chiffree from integration_pms where id = ?", String.class, ID);
            return chiffree == null ? Optional.empty() : Optional.of(SignatureReplication.lire(chiffrement.dechiffrer(chiffree)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Clé d'appairage du PMS illisible : {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public void noterContact() {
        jdbc.update("update integration_pms set dernier_contact = now() where id = ?", ID);
    }

    /** Résultat du test de connexion depuis M-FINAPP vers le PMS. */
    public record Test(boolean joignable, String message, String version) { }

    /** Interroge {@code /api/sante} du PMS : il doit répondre et être appairé avec la clé de M-FINAPP. */
    public Test tester() {
        Etat e = etat();
        if (e.url() == null) {
            return new Test(false, "Saisissez d'abord l'adresse et le port de l'hôtellerie.", null);
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(e.url() + "/api/sante"))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                return new Test(false, "Le serveur répond, mais pas comme l'application Hôtellerie (code " + r.statusCode() + ").", null);
            }
            String corps = r.body();
            boolean appaire = corps.contains("\"appaire\":true");
            String version = extraire(corps, "version");
            return new Test(true, appaire ? "Connexion établie : l'hôtellerie répond et elle est appairée."
                : "L'hôtellerie répond, mais n'a pas encore reçu la clé d'appairage : collez-la dans son écran de premier lancement.",
                version);
        } catch (Exception ex) {
            return new Test(false, "Hôtellerie injoignable à " + e.url() + " : vérifiez l'adresse, le port et que son serveur est démarré.", null);
        }
    }

    // ── Connexion unique ────────────────────────────────────────────────────

    /** Code à usage unique pour ouvrir l'hôtellerie au nom de l'utilisateur ; seule son empreinte est gardée. */
    @Transactional
    public String emettreCode(Long utilisateurId) {
        byte[] octets = new byte[32];
        HASARD.nextBytes(octets);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        jdbc.update("delete from pms_codes_sso where expire_le < now() - interval '1 day'");
        jdbc.update("insert into pms_codes_sso (empreinte, utilisateur_id, expire_le) values (?, ?, ?)",
            empreinte(code), utilisateurId, Timestamp.from(Instant.now().plus(VALIDITE_CODE)));
        return code;
    }

    /** Consomme un code : l'utilisateur s'il est valide, jamais deux fois. */
    @Transactional
    public Optional<Long> consommerCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        var lignes = jdbc.queryForList("""
            update pms_codes_sso set utilise_le = now()
            where empreinte = ? and utilise_le is null and expire_le > now()
            returning utilisateur_id
            """, Long.class, empreinte(code));
        return lignes.isEmpty() ? Optional.empty() : Optional.of(lignes.get(0));
    }

    static String empreinte(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String url(String adresse, Integer port) {
        if (adresse == null || port == null) return null;
        String base = adresse.startsWith("http://") || adresse.startsWith("https://") ? adresse : "http://" + adresse;
        return base + ":" + port;
    }

    private void assurerLigne() {
        jdbc.update("insert into integration_pms (id) values (?) on conflict (id) do nothing", ID);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String extraire(String json, String cle) {
        var m = Pattern.compile("\"" + cle + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}

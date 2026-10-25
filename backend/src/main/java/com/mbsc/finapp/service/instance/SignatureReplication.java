package com.mbsc.finapp.service.instance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cle d'appairage et signature des appels entre le serveur local et le serveur en ligne (V132).
 *
 * <p>La cle, generee par le serveur en ligne et saisie sur le serveur local, a la forme
 * {@code MBSC1.<identifiant d'appairage>.<secret>} : l'identifiant designe l'appairage (un appel d'un autre appairage est
 * refuse), le secret signe chaque appel (HMAC-SHA256). Le secret ne circule jamais : seule la signature voyage, avec un
 * horodatage (fenetre de {@link #FENETRE}) et un nonce a usage unique contre le rejeu.</p>
 */
public final class SignatureReplication {

    public static final String EN_TETE_APPAIRAGE = "X-Replication-Appairage";
    public static final String EN_TETE_HORODATAGE = "X-Replication-Horodatage";
    public static final String EN_TETE_NONCE = "X-Replication-Nonce";
    public static final String EN_TETE_SIGNATURE = "X-Replication-Signature";
    /** Version du schema (Flyway) de l'appelant : les deux serveurs doivent etre a la meme version. */
    public static final String EN_TETE_VERSION = "X-Replication-Version";

    /** Ecart d'horloge admis entre les deux serveurs (et duree de vie d'une signature). */
    public static final Duration FENETRE = Duration.ofMinutes(5);

    private static final String PREFIXE = "MBSC1";
    private static final Pattern FORME = Pattern.compile("^MBSC1\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{40,}$");
    private static final SecureRandom HASARD = new SecureRandom();

    private SignatureReplication() {}

    /** Cle d'appairage lue : identifiant d'appairage et secret. */
    public record Cle(String appairageId, String secret) {
        /** Texte complet a recopier sur le serveur local. */
        public String texte() {
            return PREFIXE + "." + appairageId + "." + secret;
        }

        /** Derniers caracteres, pour l'affichage. */
        public String fin() {
            return secret.substring(secret.length() - 6);
        }
    }

    /** Nouvelle cle : identifiant d'appairage aleatoire et secret de 256 bits. */
    public static Cle nouvelleCle() {
        byte[] octets = new byte[32];
        HASARD.nextBytes(octets);
        return new Cle(UUID.randomUUID().toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(octets));
    }

    /**
     * Lit une cle saisie (espaces et retours a la ligne d'un copier-coller retires).
     *
     * @throws IllegalArgumentException si la cle n'a pas la forme attendue
     */
    public static Cle lire(String texte) {
        String propre = texte == null ? "" : texte.replaceAll("\\s+", "");
        if (!FORME.matcher(propre).matches()) {
            throw new IllegalArgumentException("Clé d'appairage invalide : recopiez-la en entier depuis le serveur en ligne "
                + "(elle commence par « MBSC1. »).");
        }
        String[] parties = propre.split("\\.", 3);
        return new Cle(parties[1], parties[2]);
    }

    /** Nouveau nonce (a usage unique). */
    public static String nouveauNonce() {
        byte[] octets = new byte[16];
        HASARD.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }

    /**
     * Signature d'un appel : HMAC-SHA256 (hexadecimal) de l'identifiant d'appairage, de l'horodatage, du nonce, de la
     * methode, du chemin (avec sa requete) et de l'empreinte SHA-256 du corps.
     */
    public static String signer(String secret, String appairageId, long horodatage, String nonce, String methode,
                                String chemin, byte[] corps) {
        String message = String.join("\n", appairageId, Long.toString(horodatage), nonce, methode.toUpperCase(), chemin,
            sha256Hex(corps));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Signature impossible", e);
        }
    }

    /** Verifie une signature recue (comparaison a temps constant). */
    public static boolean verifier(String secret, String appairageId, long horodatage, String nonce, String methode,
                                   String chemin, byte[] corps, String signature) {
        if (signature == null) return false;
        String attendue = signer(secret, appairageId, horodatage, nonce, methode, chemin, corps);
        return MessageDigest.isEqual(attendue.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] corps) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corps == null ? new byte[0] : corps));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

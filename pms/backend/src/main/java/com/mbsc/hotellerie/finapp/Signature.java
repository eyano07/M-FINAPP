package com.mbsc.hotellerie.finapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Clé d'appairage et signature des appels vers M-FINAPP : même format et même algorithme que M-FINAPP
 * (SignatureReplication) — clé {@code MBSC1.<identifiant>.<secret>}, HMAC-SHA256 de l'identifiant, de l'horodatage, du
 * nonce, de la méthode, du chemin et de l'empreinte SHA-256 du corps. Le secret ne circule jamais.
 */
public final class Signature {

    private static final Pattern FORME = Pattern.compile("^MBSC1\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{40,}$");
    private static final SecureRandom HASARD = new SecureRandom();

    private Signature() {
    }

    public record Cle(String appairageId, String secret) {
        public String texte() {
            return "MBSC1." + appairageId + "." + secret;
        }

        public String fin() {
            return secret.substring(secret.length() - 6);
        }
    }

    public static Cle lire(String texte) {
        String propre = texte == null ? "" : texte.replaceAll("\\s+", "");
        if (!FORME.matcher(propre).matches()) {
            throw new IllegalArgumentException("Clé d'appairage invalide : recopiez-la en entier depuis M-FINAPP "
                + "(Administration › Hôtellerie ; elle commence par « MBSC1. »).");
        }
        String[] parties = propre.split("\\.", 3);
        return new Cle(parties[1], parties[2]);
    }

    public static String nonce() {
        byte[] octets = new byte[16];
        HASARD.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }

    public static String signer(String secret, String appairageId, long horodatage, String nonce, String methode,
                                String chemin, byte[] corps) {
        String message = String.join("\n", appairageId, Long.toString(horodatage), nonce, methode.toUpperCase(), chemin,
            sha256(corps));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Signature impossible", e);
        }
    }

    static String sha256(byte[] corps) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corps == null ? new byte[0] : corps));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

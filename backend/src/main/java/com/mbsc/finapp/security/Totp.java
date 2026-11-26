package com.mbsc.finapp.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Codes à usage unique fondés sur le temps (RFC 6238, HMAC-SHA1, pas de 30 secondes, 6 chiffres), compatibles avec les
 * applications d'authentification courantes (Google Authenticator, Microsoft Authenticator, FreeOTP...).
 */
public final class Totp {

    public static final int PAS_SECONDES = 30;
    private static final int CHIFFRES = 6;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom ALEATOIRE = new SecureRandom();

    private Totp() {
    }

    /** Nouveau secret : 20 octets aléatoires, encodés en base 32 (32 caractères). */
    public static String nouveauSecret() {
        byte[] octets = new byte[20];
        ALEATOIRE.nextBytes(octets);
        return base32(octets);
    }

    /** Numéro du pas de temps courant. */
    public static long pasCourant(long epochSecondes) {
        return epochSecondes / PAS_SECONDES;
    }

    /** Code attendu pour un pas donné. */
    public static String code(String secretBase32, long pas) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decoderBase32(secretBase32), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(pas).array());
            int decalage = h[h.length - 1] & 0x0f;
            int binaire = ((h[decalage] & 0x7f) << 24) | ((h[decalage + 1] & 0xff) << 16)
                | ((h[decalage + 2] & 0xff) << 8) | (h[decalage + 3] & 0xff);
            return String.format("%0" + CHIFFRES + "d", binaire % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Pas auquel le code correspond, dans une fenêtre d'un pas avant et après (décalage d'horloge du téléphone), ou -1.
     */
    public static long pasDuCode(String secretBase32, String code, long pasCourant) {
        if (code == null || !code.trim().matches("[0-9]{6}")) return -1;
        String saisi = code.trim();
        for (long p = pasCourant - 1; p <= pasCourant + 1; p++) {
            if (egal(code(secretBase32, p), saisi)) return p;
        }
        return -1;
    }

    /** Adresse otpauth:// à présenter en QR code à l'application d'authentification. */
    public static String uri(String emetteur, String compte, String secretBase32) {
        String e = encoder(emetteur);
        return "otpauth://totp/" + e + ":" + encoder(compte) + "?secret=" + secretBase32 + "&issuer=" + e
            + "&algorithm=SHA1&digits=" + CHIFFRES + "&period=" + PAS_SECONDES;
    }

    static String base32(byte[] octets) {
        StringBuilder sb = new StringBuilder();
        int tampon = 0, bits = 0;
        for (byte b : octets) {
            tampon = (tampon << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((tampon >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((tampon << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decoderBase32(String texte) {
        String s = texte.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int tampon = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Secret base 32 invalide");
            tampon = (tampon << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((tampon >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    private static boolean egal(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static String encoder(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}

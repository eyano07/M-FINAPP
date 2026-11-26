package com.mbsc.finapp.service.ia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement des secrets stockés en base (clés d'API de l'IA) : AES-256-GCM, nonce aléatoire par valeur,
 * résultat {@code base64(nonce || chiffré)}.
 *
 * <p>La clé de chiffrement est {@code APP_SECRETS_KEY} (32 octets en base64). À défaut, elle est dérivée du
 * secret JWT avec un avertissement : l'application fonctionne sans configuration supplémentaire, mais
 * changer le secret JWT rendrait alors les clés enregistrées illisibles (l'administrateur les ressaisit).</p>
 */
@Service
public class ChiffrementSecretService {

    private static final Logger log = LoggerFactory.getLogger(ChiffrementSecretService.class);
    private static final int NONCE_OCTETS = 12;
    private static final int ETIQUETTE_BITS = 128;

    private final SecretKeySpec cle;
    private final SecureRandom aleatoire = new SecureRandom();

    public ChiffrementSecretService(@Value("${app.secrets.key:}") String cleSecrets,
                                    @Value("${app.jwt.secret:}") String secretJwt) {
        byte[] octets;
        if (StringUtils.hasText(cleSecrets)) {
            try {
                octets = Base64.getDecoder().decode(cleSecrets.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_SECRETS_KEY doit être une clé de 32 octets encodée en base64.");
            }
            if (octets.length != 32) {
                throw new IllegalStateException("APP_SECRETS_KEY doit contenir exactement 32 octets (base64) : "
                    + "générez-la avec « openssl rand -base64 32 ».");
            }
        } else {
            log.warn("APP_SECRETS_KEY absente : les clés d'API de l'IA sont chiffrées avec une clé dérivée du secret "
                + "JWT. Définissez APP_SECRETS_KEY (openssl rand -base64 32) pour les dissocier.");
            octets = sha256("mbsc-secrets:" + secretJwt);
        }
        this.cle = new SecretKeySpec(octets, "AES");
    }

    public String chiffrer(String clair) {
        try {
            byte[] nonce = new byte[NONCE_OCTETS];
            aleatoire.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(ETIQUETTE_BITS, nonce));
            byte[] chiffre = c.doFinal(clair.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + chiffre.length).put(nonce).put(chiffre).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    /** @return le texte clair, ou {@code null} si la valeur est illisible (clé de chiffrement changée, donnée altérée). */
    public String dechiffrer(String valeur) {
        if (!StringUtils.hasText(valeur)) return null;
        try {
            byte[] tout = Base64.getDecoder().decode(valeur);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(ETIQUETTE_BITS, tout, 0, NONCE_OCTETS));
            return new String(c.doFinal(tout, NONCE_OCTETS, tout.length - NONCE_OCTETS), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Secret illisible : la clé de chiffrement a changé ou la valeur est altérée ; ressaisissez la clé.");
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

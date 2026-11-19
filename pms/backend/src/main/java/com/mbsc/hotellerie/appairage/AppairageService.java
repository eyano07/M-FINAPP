package com.mbsc.hotellerie.appairage;

import com.mbsc.hotellerie.finapp.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Clé d'appairage avec M-FINAPP, collée une fois à l'écran de premier lancement. Elle est conservée chiffrée en base
 * (AES-GCM, clé dérivée de PMS_SECRETS_KEY) : une copie de la base seule ne permet pas de signer des appels.
 */
@Service
public class AppairageService {

    private static final Logger log = LoggerFactory.getLogger(AppairageService.class);
    private static final SecureRandom HASARD = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final byte[] cleAes;
    private volatile Optional<Signature.Cle> cache;

    public AppairageService(JdbcTemplate jdbc, @Value("${app.secrets-key:}") String secretsKey) {
        this.jdbc = jdbc;
        if (secretsKey == null || secretsKey.isBlank()) {
            throw new IllegalStateException("PMS_SECRETS_KEY est obligatoire (openssl rand -base64 32) : elle chiffre la clé "
                + "d'appairage avec M-FINAPP.");
        }
        try {
            this.cleAes = MessageDigest.getInstance("SHA-256").digest(secretsKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Signature.Cle> cle() {
        Optional<Signature.Cle> c = cache;
        if (c == null) {
            String chiffree = jdbc.queryForObject("select cle_chiffree from parametres_pms where id = 1", String.class);
            try {
                c = chiffree == null ? Optional.empty() : Optional.of(Signature.lire(dechiffrer(chiffree)));
            } catch (RuntimeException e) {
                log.warn("Clé d'appairage illisible (PMS_SECRETS_KEY changée ?) : {}", e.getMessage());
                c = Optional.empty();
            }
            cache = c;
        }
        return c;
    }

    public boolean appaire() {
        return cle().isPresent();
    }

    public Signature.Cle enregistrer(String texte, String auteur) {
        Signature.Cle cle = Signature.lire(texte);
        jdbc.update("update parametres_pms set cle_chiffree = ?, appaire_le = now(), appaire_par = ? where id = 1",
            chiffrer(cle.texte()), auteur);
        cache = Optional.of(cle);
        return cle;
    }

    String chiffrer(String clair) {
        try {
            byte[] iv = new byte[12];
            HASARD.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cleAes, "AES"), new GCMParameterSpec(128, iv));
            byte[] chiffre = c.doFinal(clair.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + chiffre.length).put(iv).put(chiffre).array());
        } catch (Exception e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    String dechiffrer(String valeur) {
        try {
            byte[] tout = Base64.getDecoder().decode(valeur);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cleAes, "AES"), new GCMParameterSpec(128, tout, 0, 12));
            return new String(c.doFinal(tout, 12, tout.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Déchiffrement impossible", e);
        }
    }
}

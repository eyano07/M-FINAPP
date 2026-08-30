package com.mbsc.finapp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protection anti-bruteforce du login : après {@code maxTentatives} échecs
 * consécutifs pour un même identifiant, le compte est temporairement bloqué
 * pendant {@code dureeBlocage}. Un login réussi remet le compteur à zéro.
 *
 * <p>Implémentation en mémoire, suffisante pour un déploiement mono-instance ;
 * remplacer par un cache partagé (Redis) en cas de mise à l'échelle.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private record Tentatives(int echecs, Instant dernierEchec) {
    }

    private final Map<String, Tentatives> tentatives = new ConcurrentHashMap<>();
    private final int maxTentatives;
    private final Duration dureeBlocage;

    public LoginAttemptService(
        @Value("${app.security.login-max-attempts:5}") int maxTentatives,
        @Value("${app.security.login-lock-minutes:15}") long lockMinutes
    ) {
        this.maxTentatives = maxTentatives;
        this.dureeBlocage = Duration.ofMinutes(lockMinutes);
    }

    /** @return true si l'identifiant est actuellement bloqué. */
    public boolean estBloque(String email) {
        Tentatives t = tentatives.get(normaliser(email));
        if (t == null || t.echecs() < maxTentatives) {
            return false;
        }
        if (t.dernierEchec().plus(dureeBlocage).isBefore(Instant.now())) {
            tentatives.remove(normaliser(email));
            return false;
        }
        return true;
    }

    public long minutesRestantes(String email) {
        Tentatives t = tentatives.get(normaliser(email));
        if (t == null) {
            return 0;
        }
        Duration restant = Duration.between(Instant.now(), t.dernierEchec().plus(dureeBlocage));
        return Math.max(1, restant.toMinutes());
    }

    public void enregistrerEchec(String email) {
        String cle = normaliser(email);
        Tentatives t = tentatives.merge(cle,
            new Tentatives(1, Instant.now()),
            (ancien, nouveau) -> new Tentatives(ancien.echecs() + 1, Instant.now()));
        if (t.echecs() >= maxTentatives) {
            log.warn("Compte {} temporairement bloque apres {} echecs de connexion", cle, t.echecs());
        }
    }

    public void enregistrerSucces(String email) {
        tentatives.remove(normaliser(email));
    }

    private String normaliser(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

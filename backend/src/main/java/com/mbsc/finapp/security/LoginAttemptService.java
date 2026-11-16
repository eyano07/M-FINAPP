package com.mbsc.finapp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Protection anti-bruteforce du login : après {@code maxTentatives} échecs
 * consécutifs pour un même identifiant, le compte est temporairement bloqué
 * pendant {@code dureeBlocage}. Un login réussi remet le compteur à zéro.
 *
 * <p>Les échecs sont gardés en base (table {@code tentatives_connexion}, propre à chaque serveur) : un redémarrage
 * du serveur ne lève plus le blocage (audit SYSCOHADA du 10/11/2026, C-23). Chaque écriture a sa propre transaction :
 * la connexion refusée annule la sienne, pas le compteur.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private record Tentatives(int echecs, Instant dernierEchec) {
    }

    private final JdbcTemplate jdbc;
    private final int maxTentatives;
    private final Duration dureeBlocage;

    public LoginAttemptService(
        JdbcTemplate jdbc,
        @Value("${app.security.login-max-attempts:5}") int maxTentatives,
        @Value("${app.security.login-lock-minutes:15}") long lockMinutes
    ) {
        this.jdbc = jdbc;
        this.maxTentatives = maxTentatives;
        this.dureeBlocage = Duration.ofMinutes(lockMinutes);
    }

    /** @return true si l'identifiant est actuellement bloqué. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean estBloque(String email) {
        Tentatives t = lire(normaliser(email));
        if (t == null || t.echecs() < maxTentatives) {
            return false;
        }
        if (t.dernierEchec().plus(dureeBlocage).isBefore(Instant.now())) {
            jdbc.update("delete from tentatives_connexion where identifiant = ?", normaliser(email));
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long minutesRestantes(String email) {
        Tentatives t = lire(normaliser(email));
        if (t == null) {
            return 0;
        }
        Duration restant = Duration.between(Instant.now(), t.dernierEchec().plus(dureeBlocage));
        return Math.max(1, restant.toMinutes());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrerEchec(String email) {
        String cle = normaliser(email);
        Integer echecs = jdbc.queryForObject("""
            insert into tentatives_connexion (identifiant, echecs, dernier_echec) values (?, 1, ?)
            on conflict (identifiant) do update set echecs = tentatives_connexion.echecs + 1,
                dernier_echec = excluded.dernier_echec
            returning echecs
            """, Integer.class, cle, Timestamp.from(Instant.now()));
        if (echecs != null && echecs >= maxTentatives) {
            log.warn("Compte {} temporairement bloque apres {} echecs de connexion", cle, echecs);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrerSucces(String email) {
        jdbc.update("delete from tentatives_connexion where identifiant = ?", normaliser(email));
    }

    private Tentatives lire(String cle) {
        List<Tentatives> l = jdbc.query("select echecs, dernier_echec from tentatives_connexion where identifiant = ?",
            (rs, i) -> new Tentatives(rs.getInt(1), rs.getTimestamp(2).toInstant()), cle);
        return l.isEmpty() ? null : l.get(0);
    }

    private String normaliser(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

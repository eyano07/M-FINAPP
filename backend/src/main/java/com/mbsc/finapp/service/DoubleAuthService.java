package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.security.Totp;
import com.mbsc.finapp.service.ia.ChiffrementSecretService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Double authentification par code à usage unique (audit SYSCOHADA du 10/11/2026, C-23).
 *
 * <p>Chaque utilisateur peut l'activer depuis son profil : un secret est créé, présenté en QR code à son application
 * d'authentification, et n'est actif qu'une fois un premier code confirmé. L'administrateur peut ensuite la rendre
 * <b>obligatoire</b> pour les rôles qui engagent l'argent ou les comptes (DFIN, DA, DG, ADMIN, CAISSIER) : ces comptes
 * sont alors bloqués sur la page d'activation tant qu'ils ne l'ont pas faite. Le secret est chiffré avec la clé du
 * serveur ; un code déjà utilisé ne sert pas deux fois.</p>
 */
@Service
@RequiredArgsConstructor
public class DoubleAuthService {

    private static final Logger log = LoggerFactory.getLogger(DoubleAuthService.class);

    /** Rôles soumis à la double authentification quand elle est obligatoire. */
    public static final Set<RoleType> ROLES_SENSIBLES =
        EnumSet.of(RoleType.DFIN, RoleType.DA, RoleType.DG, RoleType.ADMIN, RoleType.CAISSIER);

    private final JdbcTemplate jdbc;
    private final ChiffrementSecretService chiffrement;
    private final CurrentUserProvider currentUser;

    /** État vu par l'utilisateur : active, obligatoire pour lui, enrôlement commencé. */
    public record Etat(boolean active, boolean obligatoire, boolean enrolementEnCours) {
    }

    /** Secret à présenter en QR code au début de l'enrôlement. */
    public record Enrolement(String secret, String uri) {
    }

    // ---------------------------------------------------------------------
    // Profil de l'utilisateur connecté
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Etat etat() {
        User u = currentUser.requireUser();
        Map<String, Object> ligne = ligne(u.getId());
        boolean active = ligne != null && Boolean.TRUE.equals(ligne.get("active"));
        return new Etat(active, obligatoirePour(u), ligne != null && !active);
    }

    @Transactional
    public Enrolement demarrer() {
        User u = currentUser.requireUser();
        if (estActive(u.getId())) {
            throw new TransitionInvalideException("La double authentification est déjà active sur votre compte.");
        }
        String secret = Totp.nouveauSecret();
        jdbc.update("""
            insert into double_authentification (user_id, secret_chiffre, active) values (?, ?, false)
            on conflict (user_id) do update set secret_chiffre = excluded.secret_chiffre, active = false,
                activee_le = null, dernier_pas = null
            """, u.getId(), chiffrement.chiffrer(secret));
        return new Enrolement(secret, Totp.uri("MBSC Finapp", u.getEmail(), secret));
    }

    @Transactional
    public Etat confirmer(String code) {
        User u = currentUser.requireUser();
        Map<String, Object> ligne = ligne(u.getId());
        if (ligne == null) {
            throw new TransitionInvalideException("Commencez par afficher le QR code de votre compte.");
        }
        long pas = verifierCode(u.getId(), ligne, code);
        if (pas < 0) {
            throw new IllegalArgumentException("Code incorrect : saisissez le code à 6 chiffres affiché par l'application.");
        }
        jdbc.update("update double_authentification set active = true, activee_le = ?, dernier_pas = ? where user_id = ?",
            java.sql.Timestamp.from(Instant.now()), pas, u.getId());
        log.info("Double authentification activee pour {}", u.getEmail());
        return etat();
    }

    @Transactional
    public Etat desactiver(String code) {
        User u = currentUser.requireUser();
        if (obligatoirePour(u)) {
            throw new TransitionInvalideException(
                "La double authentification est obligatoire pour votre rôle : elle ne se désactive pas.");
        }
        Map<String, Object> ligne = ligne(u.getId());
        if (ligne != null && Boolean.TRUE.equals(ligne.get("active")) && verifierCode(u.getId(), ligne, code) < 0) {
            throw new IllegalArgumentException("Code incorrect.");
        }
        jdbc.update("delete from double_authentification where user_id = ?", u.getId());
        log.info("Double authentification desactivee par {}", u.getEmail());
        return etat();
    }

    // ---------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------

    /** Téléphone perdu : l'administrateur efface l'enrôlement ; l'utilisateur en refait un à la connexion suivante. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void reinitialiser(Long userId) {
        jdbc.update("delete from double_authentification where user_id = ?", userId);
        log.warn("Double authentification reinitialisee pour l'utilisateur {} par {}", userId,
            currentUser.requireUser().getEmail());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public boolean definirObligatoire(boolean obligatoire) {
        User admin = currentUser.requireUser();
        if (obligatoire && !estActive(admin.getId())) {
            // Sinon l'administrateur se bloquerait lui-même dès sa requête suivante.
            throw new TransitionInvalideException("Activez d'abord votre propre double authentification (Mon profil) "
                + "avant de la rendre obligatoire.");
        }
        jdbc.update("update parametres_securite set double_auth_obligatoire = ?, maj_par_id = ?, maj_le = now() where id = 1",
            obligatoire, currentUser.requireUser().getId());
        log.warn("Double authentification {} pour les roles sensibles", obligatoire ? "obligatoire" : "facultative");
        return obligatoire;
    }

    @Transactional(readOnly = true)
    public boolean obligatoire() {
        List<Boolean> v = jdbc.queryForList("select double_auth_obligatoire from parametres_securite where id = 1",
            Boolean.class);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    // ---------------------------------------------------------------------
    // Connexion
    // ---------------------------------------------------------------------

    /** true si l'utilisateur a une double authentification active : la connexion exige alors un code. */
    @Transactional(readOnly = true)
    public boolean estActive(Long userId) {
        Map<String, Object> ligne = ligne(userId);
        return ligne != null && Boolean.TRUE.equals(ligne.get("active"));
    }

    /** Vérifie le code d'une connexion ; un code accepté ne pourra plus resservir. */
    @Transactional
    public boolean verifierConnexion(Long userId, String code) {
        Map<String, Object> ligne = ligne(userId);
        if (ligne == null || !Boolean.TRUE.equals(ligne.get("active"))) return true;
        long pas = verifierCode(userId, ligne, code);
        if (pas < 0) return false;
        jdbc.update("update double_authentification set dernier_pas = ? where user_id = ?", pas, userId);
        return true;
    }

    /** true si l'utilisateur doit activer la double authentification avant de continuer. */
    @Transactional(readOnly = true)
    public boolean doitActiver(User u) {
        return obligatoirePour(u) && !estActive(u.getId());
    }

    private boolean obligatoirePour(User u) {
        return obligatoire() && u.getRoles().stream().map(Role::getNom).anyMatch(ROLES_SENSIBLES::contains);
    }

    private long verifierCode(Long userId, Map<String, Object> ligne, String code) {
        String secret = chiffrement.dechiffrer((String) ligne.get("secret_chiffre"));
        if (secret == null) {
            throw new TransitionInvalideException("Le secret de double authentification est illisible (clé du serveur "
                + "changée) : demandez à l'administrateur de réinitialiser votre double authentification.");
        }
        long pas = Totp.pasDuCode(secret, code, Totp.pasCourant(Instant.now().getEpochSecond()));
        Number dernier = (Number) ligne.get("dernier_pas");
        if (pas >= 0 && dernier != null && pas <= dernier.longValue()) {
            log.warn("Code de double authentification rejoue pour l'utilisateur {}", userId);
            return -1;
        }
        return pas;
    }

    private Map<String, Object> ligne(Long userId) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from double_authentification where user_id = ?", userId);
        return l.isEmpty() ? null : l.get(0);
    }
}

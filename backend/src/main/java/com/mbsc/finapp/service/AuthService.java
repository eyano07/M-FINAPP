package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.auth.AuthResponse;
import com.mbsc.finapp.dto.auth.LoginRequest;
import com.mbsc.finapp.dto.auth.RefreshRequest;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.JwtService;
import com.mbsc.finapp.security.LoginAttemptService;
import com.mbsc.finapp.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

/**
 * Service d'authentification : delegue la verification des identifiants a Spring
 * Security puis emet une paire de jetons (access + refresh).
 * Les tentatives sont journalisees a des fins d'audit de securite (sans jamais
 * tracer le mot de passe).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Identifiant inconnu ou mot de passe faux. Le message de Spring Security (« Bad credentials », en anglais, ou sa
     * traduction selon la langue du navigateur) n'est jamais montré : l'écran de connexion affiche celui-ci. Il ne dit
     * pas lequel des deux est faux, pour ne pas révéler si un identifiant existe.
     */
    static final String MESSAGE_IDENTIFIANTS_INCORRECTS = "Identifiant ou mot de passe incorrect.";

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;
    private final com.mbsc.finapp.audit.AuditService audit;
    private final DoubleAuthService doubleAuth;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.debug("Tentative de connexion pour {}", request.email());

        // Anti-bruteforce : blocage temporaire apres plusieurs echecs consecutifs.
        if (loginAttemptService.estBloque(request.email())) {
            log.warn("Connexion refusee pour {} : compte temporairement bloque", request.email());
            audit.authentification("CONNEXION", request.email(), null, false, "compte temporairement bloque");
            throw new BadCredentialsException(
                "Trop de tentatives de connexion. Reessayez dans "
                + loginAttemptService.minutesRestantes(request.email()) + " minute(s).");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.motDePasse())
            );
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            // Double authentification (audit SYSCOHADA du 10/11/2026, C-23) : le mot de passe ne suffit plus.
            Long userId = principal.getDomainUser().getId();
            if (doubleAuth.estActive(userId)) {
                if (request.codeDoubleAuth() == null || request.codeDoubleAuth().isBlank()) {
                    throw new com.mbsc.finapp.security.DoubleAuthException(
                        com.mbsc.finapp.security.DoubleAuthException.REQUISE,
                        "Saisissez le code à 6 chiffres affiché par votre application d'authentification.");
                }
                if (!doubleAuth.verifierConnexion(userId, request.codeDoubleAuth())) {
                    loginAttemptService.enregistrerEchec(request.email());
                    audit.authentification("CONNEXION", principal.getUsername(), userId, false,
                        "code de double authentification incorrect");
                    throw new com.mbsc.finapp.security.DoubleAuthException(
                        com.mbsc.finapp.security.DoubleAuthException.INCORRECT,
                        "Code de double authentification incorrect ou déjà utilisé.");
                }
            }
            loginAttemptService.enregistrerSucces(request.email());
            log.info("Connexion reussie : {}", principal.getUsername());
            audit.authentification("CONNEXION", principal.getUsername(), principal.getDomainUser().getId(), true, null);
            return buildResponse(principal);
        } catch (BadCredentialsException ex) {
            // Audit : on trace l'echec sans divulguer la cause exacte cote client
            loginAttemptService.enregistrerEchec(request.email());
            log.warn("Echec de connexion pour {} : identifiants invalides", request.email());
            audit.authentification("CONNEXION", request.email(), null, false, "identifiants invalides");
            throw new BadCredentialsException(MESSAGE_IDENTIFIANTS_INCORRECTS, ex);
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        final String email;
        try {
            email = jwtService.extractUsername(request.refreshToken());
        } catch (RuntimeException ex) {
            log.warn("Jeton de rafraichissement illisible : {}", ex.getMessage());
            throw new BadCredentialsException("Jeton de rafraichissement invalide");
        }
        // Seul un jeton de type "refresh" est accepte ici : un jeton d'acces
        // vole ne permet pas d'obtenir une nouvelle paire de jetons.
        if (!jwtService.isRefreshToken(request.refreshToken())) {
            log.warn("Jeton non-refresh presente au rafraichissement pour {}", email);
            throw new BadCredentialsException("Jeton de rafraichissement invalide");
        }
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BadCredentialsException("Jeton de rafraichissement invalide"));
        UserPrincipal principal = new UserPrincipal(user);
        if (!jwtService.isTokenValid(request.refreshToken(), principal)) {
            log.warn("Jeton de rafraichissement expire ou invalide pour {}", email);
            throw new BadCredentialsException("Jeton de rafraichissement expire ou invalide");
        }
        log.info("Jetons rafraichis pour {}", email);
        return buildResponse(principal);
    }

    /**
     * Déconnexion côté serveur : tous les jetons de l'utilisateur, sur tous
     * ses appareils, cessent d'être acceptés (voir User.versionJetons). Sans
     * elle, un jeton de rafraîchissement copié restait utilisable 7 jours
     * après la déconnexion (audit sécurité du 05/10/2026, S-02).
     */
    @Transactional
    public void deconnecter(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.revoquerJetons();
            log.info("Deconnexion : jetons revoques pour {}", user.getEmail());
            audit.authentification("DECONNEXION", user.getEmail(), user.getId(), true, null);
        });
    }

    /**
     * Connexion unique depuis l'application Hôtellerie (V152) : le code à usage unique émis par M-FINAPP a été vérifié
     * par l'appelant (PmsSsoService) ; on ouvre ici la session de son titulaire, comme après un mot de passe, en la
     * consignant au journal d'audit. Un compte désactivé est refusé.
     */
    @Transactional(readOnly = true)
    public AuthResponse ouvrirSessionUnique(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("Compte introuvable"));
        UserPrincipal principal = new UserPrincipal(user);
        if (!principal.isEnabled()) {
            audit.authentification("CONNEXION_HOTELLERIE", user.getEmail(), userId, false, "compte desactive");
            throw new BadCredentialsException("Compte désactivé.");
        }
        audit.authentification("CONNEXION_HOTELLERIE", user.getEmail(), userId, true, "connexion unique vers l'hotellerie");
        return buildResponse(principal);
    }

    private AuthResponse buildResponse(UserPrincipal principal) {
        User user = principal.getDomainUser();
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = jwtService.generateRefreshToken(principal);

        AuthResponse.UserInfo info = new AuthResponse.UserInfo(
            user.getId(),
            user.getEmail(),
            user.getNom(),
            user.getPrenom(),
            user.getTelephone(),
            user.getPhotoCheminStockage() != null ? "/profil/photo" : null,
            user.getRoles().stream().map(Role::getNom).map(Enum::name).collect(Collectors.toSet()),
            user.isDoitChangerMotDePasse(),
            doubleAuth.doitActiver(user)
        );

        return new AuthResponse(
            accessToken,
            refreshToken,
            "Bearer",
            jwtService.getAccessTokenExpirationMs(),
            info
        );
    }
}

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

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        log.debug("Tentative de connexion pour {}", request.email());

        // Anti-bruteforce : blocage temporaire apres plusieurs echecs consecutifs.
        if (loginAttemptService.estBloque(request.email())) {
            log.warn("Connexion refusee pour {} : compte temporairement bloque", request.email());
            throw new BadCredentialsException(
                "Trop de tentatives de connexion. Reessayez dans "
                + loginAttemptService.minutesRestantes(request.email()) + " minute(s).");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.motDePasse())
            );
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            loginAttemptService.enregistrerSucces(request.email());
            log.info("Connexion reussie : {}", principal.getUsername());
            return buildResponse(principal);
        } catch (BadCredentialsException ex) {
            // Audit : on trace l'echec sans divulguer la cause exacte cote client
            loginAttemptService.enregistrerEchec(request.email());
            log.warn("Echec de connexion pour {} : identifiants invalides", request.email());
            throw ex;
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
            user.getRoles().stream().map(Role::getNom).map(Enum::name).collect(Collectors.toSet())
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

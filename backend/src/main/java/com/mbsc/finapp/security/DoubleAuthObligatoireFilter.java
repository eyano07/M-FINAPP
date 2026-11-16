package com.mbsc.finapp.security;

import com.mbsc.finapp.service.DoubleAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Double authentification obligatoire (audit SYSCOHADA du 10/11/2026, C-23) : un compte d'un rôle sensible (DFIN, DA,
 * DG, ADMIN, CAISSIER) qui ne l'a pas encore activée ne peut rien faire d'autre que l'activer, comme un mot de passe
 * provisoire ne laisse faire que son changement (réponse 403, code {@value #CODE}).
 */
@Component
@RequiredArgsConstructor
public class DoubleAuthObligatoireFilter extends OncePerRequestFilter {

    public static final String CODE = "DOUBLE_AUTH_A_ACTIVER";

    private final DoubleAuthService doubleAuth;

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)
            || estOuvert(request.getMethod(), request.getServletPath())
            || auth.getAuthorities().stream().noneMatch(a -> DoubleAuthService.ROLES_SENSIBLES.stream()
                .anyMatch(r -> ("ROLE_" + r.name()).equals(a.getAuthority())))
            || !doubleAuth.doitActiver(principal.getDomainUser())) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"error\":\"Forbidden\",\"code\":\"" + CODE + "\",\"message\":\"La double authentification est "
                + "obligatoire pour votre rôle : activez-la depuis votre profil pour continuer.\"}");
    }

    /** Requêtes permises avant l'activation : jetons, profil, activation elle-même, et ce qui est public. */
    static boolean estOuvert(String methode, String chemin) {
        if (chemin.equals("/auth") || chemin.startsWith("/auth/") || chemin.startsWith("/profil/double-auth")) {
            return true;
        }
        if (HttpMethod.GET.matches(methode)) {
            return chemin.equals("/profil") || chemin.equals("/profil/photo") || chemin.equals("/parametres")
                || chemin.equals("/parametres/logo") || chemin.equals("/health") || chemin.equals("/instance/etat");
        }
        return chemin.equals("/profil/mot-de-passe") && HttpMethod.PUT.matches(methode);
    }
}

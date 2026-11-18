package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.service.widget.WidgetService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Widgets Android (18/11/2026) : une requête GET vers {@code /widget/**} qui présente l'en-tête {@value #ENTETE} est
 * authentifiée comme le compte qui a reçu ce jeton (WidgetService.emettre), avec ses rôles : le résumé suit donc les
 * mêmes droits que le site. Ce jeton n'ouvre rien d'autre : hors de {@code /widget/}, le filtre ne fait rien.
 * Un compte désactivé ne lit plus rien.
 */
@Component
public class WidgetJetonFilter extends OncePerRequestFilter {

    public static final String ENTETE = "X-Jeton-Widget";

    private final WidgetService widgets;

    public WidgetJetonFilter(@Lazy WidgetService widgets) {
        this.widgets = widgets;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !request.getServletPath().startsWith("/widget/")
            || request.getHeader(ENTETE) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<User> compte = widgets.utilisateurDe(request.getHeader(ENTETE));
            compte.filter(User::isActif).map(UserPrincipal::new).ifPresent(p ->
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities())));
        }
        filterChain.doFilter(request, response);
    }
}

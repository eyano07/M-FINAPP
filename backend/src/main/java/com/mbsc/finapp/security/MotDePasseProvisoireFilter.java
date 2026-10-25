package com.mbsc.finapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Compte au mot de passe provisoire (choisi par l'administrateur a la creation du compte, ou redefini par lui : V131) :
 * tant que l'utilisateur ne l'a pas remplace par le sien, seuls lui sont ouverts la connexion et les jetons
 * ({@code /auth/**}), la lecture de son profil et le changement de mot de passe. Toute autre requete recoit 403
 * avec le code {@value #CODE}, que l'ecran traduit en renvoi vers la page de changement de mot de passe — l'API ne
 * peut donc pas servir a contourner l'ecran.
 */
@Component
public class MotDePasseProvisoireFilter extends OncePerRequestFilter {

    /** Code d'erreur reconnu par l'ecran (et par le poste de caisse desktop, qui affiche le message). */
    public static final String CODE = "MOT_DE_PASSE_A_CHANGER";

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)
            || !principal.getDomainUser().isDoitChangerMotDePasse()
            || estOuvert(request.getMethod(), request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"error\":\"Forbidden\",\"code\":\"" + CODE + "\",\"message\":\"Votre mot de passe est provisoire : "
                + "choisissez votre propre mot de passe pour continuer.\"}");
    }

    /**
     * Requetes permises tant que le mot de passe est provisoire : jetons et deconnexion, lecture du profil, changement
     * de mot de passe, et ce qui est public de toute facon (identite visuelle de l'entreprise, etat du serveur).
     */
    static boolean estOuvert(String methode, String chemin) {
        if (chemin.equals("/auth") || chemin.startsWith("/auth/")) {
            return true;
        }
        if (HttpMethod.GET.matches(methode)) {
            return chemin.equals("/profil") || chemin.equals("/profil/photo") || chemin.equals("/parametres")
                || chemin.equals("/parametres/logo") || chemin.equals("/health")
                || chemin.equals("/instance/etat");   // la page explique ou changer le mot de passe sur une copie en ligne
        }
        return chemin.equals("/profil/mot-de-passe") && HttpMethod.PUT.matches(methode);
    }
}

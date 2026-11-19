package com.mbsc.finapp.security;

import com.mbsc.finapp.service.exercice.ExerciceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Consultation d'un exercice clôturé (19/11/2026) : le site envoie l'en-tête {@value #ENTETE} (année) quand
 * l'utilisateur a choisi un autre exercice que l'exercice en cours dans la barre du haut. Si cet exercice est clôturé
 * définitivement, toute requête qui modifie des données est refusée (423, code {@value #CODE}) : on y lit et on y
 * imprime (exports, PDF, Excel : requêtes GET, selon les droits de chacun), on n'y saisit rien. Restent permis : la
 * connexion et les jetons, son propre compte, les notifications, les téléphones et l'analyse IA des états financiers
 * (lecture). Le verrou de période protège en plus, quel que soit l'en-tête, toute écriture datée d'un exercice clos.
 */
@Component
public class ExerciceConsulteFilter extends OncePerRequestFilter {

    public static final String ENTETE = "X-Exercice";
    public static final String CODE = "EXERCICE_CLOTURE_LECTURE_SEULE";

    private final ExerciceService exercices;

    public ExerciceConsulteFilter(ExerciceService exercices) {
        this.exercices = exercices;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Integer annee = annee(request.getHeader(ENTETE));
        if (annee == null || estPermise(request.getMethod(), request.getServletPath()) || !exercices.estCloture(annee)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(423);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Locked\",\"code\":\"" + CODE + "\",\"message\":\"L'exercice " + annee
            + " est clôturé : il se consulte en lecture seule (impression permise selon vos droits). Revenez à "
            + "l'exercice en cours dans la barre du haut pour saisir.\"}");
    }

    static Integer annee(String valeur) {
        if (valeur == null || !valeur.matches("\\d{4}")) return null;
        return Integer.valueOf(valeur);
    }

    static boolean estPermise(String methode, String chemin) {
        if (methode.equals("GET") || methode.equals("HEAD") || methode.equals("OPTIONS")) return true;
        if (chemin.equals("/auth") || chemin.startsWith("/auth/")) return true;
        if (chemin.equals("/profil") || chemin.startsWith("/profil/")) return true;
        if (chemin.equals("/notifications") || chemin.startsWith("/notifications/")) return true;
        if (chemin.equals("/appareils") || chemin.startsWith("/appareils/")) return true;
        return chemin.equals("/comptabilite/etats-financiers/analyse-ia");
    }
}

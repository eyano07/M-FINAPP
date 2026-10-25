package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.service.instance.InstanceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Serveur en lecture seule (V132) : copie en ligne d'un serveur local (MIROIR) ou ancien serveur local (LOCAL_ARRETE).
 * Toute requete qui modifie des donnees est refusee (423, code {@value #CODE}) avec une explication, sauf :
 * la connexion et les jetons, les appels du serveur local ({@code /replication/**}), la lecture des notifications,
 * l'administration du mode du serveur et — sur la copie en ligne seulement — les actions relayees au serveur local
 * (voir ActionsDistantesService) : validations DFIN / DA, soumission d'un brouillon, creation d'une note de frais, et
 * activation / desactivation d'un utilisateur par l'administrateur (effet immediat sur la copie).
 */
@Component
public class MiroirLectureSeuleFilter extends OncePerRequestFilter {

    public static final String CODE = "SERVEUR_EN_LECTURE_SEULE";

    /** Actions sur une note relayees depuis la copie en ligne vers le serveur local. */
    public static final Pattern ACTIONS_RELAYEES =
        Pattern.compile("^/notes-frais/\\d+/(verifier|transmettre|valider|rejeter|observation|priorite|soumettre)$");
    /** Activation / desactivation d'un utilisateur par l'administrateur (immediate sur la copie, relayee ensuite). */
    public static final Pattern ACTIVATION_UTILISATEUR = Pattern.compile("^/admin/users/\\d+/actif$");

    private final InstanceService instance;

    public MiroirLectureSeuleFilter(InstanceService instance) {
        this.instance = instance;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        ModeInstance mode = instance.mode();
        if (!mode.lectureSeule() || estPermise(mode, request.getMethod(), request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(423);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Locked\",\"code\":\"" + CODE + "\",\"message\":\"" + message(mode) + "\"}");
    }

    static boolean estPermise(ModeInstance mode, String methode, String chemin) {
        if (methode.equals("GET") || methode.equals("HEAD") || methode.equals("OPTIONS")) return true;
        if (chemin.equals("/auth") || chemin.startsWith("/auth/")) return true;
        if (chemin.startsWith("/replication/")) return true;
        if (chemin.startsWith("/notifications")) return true;
        if (chemin.startsWith("/admin/instance")) return true;
        if (mode != ModeInstance.MIROIR) return false;
        if (methode.equals("POST")) {
            return ACTIONS_RELAYEES.matcher(chemin).matches()
                || chemin.equals("/actions-distantes/notes")          // creation d'une note (relayee)
                || chemin.equals("/notes-frais/controle-budgetaire");  // calcul du formulaire de creation, sans ecriture
        }
        return methode.equals("PATCH") && ACTIVATION_UTILISATEUR.matcher(chemin).matches();
    }

    static String message(ModeInstance mode) {
        return mode == ModeInstance.MIROIR
            ? "Ce serveur est la copie en ligne du serveur de l'entreprise : les saisies se font sur le serveur local "
                + "(réseau de l'entreprise). Ici, seules la création et la validation des notes de frais, et l'activation "
                + "des comptes par l'administrateur, sont possibles."
            : "Ce serveur n'est plus le serveur principal : il ne reçoit plus de saisies. Utilisez le serveur en ligne.";
    }
}

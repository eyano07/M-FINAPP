package com.mbsc.finapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Application Android (17/11/2026) : elle sert à valider les notes de frais et à consulter le tableau de bord, les notes
 * de frais et la comptabilité ; le caissier y tient en plus la trésorerie et la vente. Une requête venue de
 * l'application (reconnue à son agent utilisateur {@value #AGENT}, ajouté par la vue web de l'application à toutes ses
 * requêtes) qui modifie des données est refusée (403, code {@value #CODE}), sauf : la connexion et les jetons, son
 * propre compte ({@code /profil} : mot de passe, double authentification, préférences), les notifications,
 * l'inscription du téléphone aux notifications push, les décisions sur une note (vérifier, transmettre, approuver,
 * rejeter, observation, priorité) et sur un budget ({@link #DECISIONS_BUDGET}), pour le caissier les saisies de {@link #SAISIES_CAISSIER}, pour le comptable
 * celles de {@link #SAISIES_COMPTABLE}, pour le DFIN les corrections de {@link #CORRECTIONS_DFIN} et pour le DA les
 * seuils de priorité ({@value #SEUILS_PRIORITE}) en plus de ses décisions (valider, rejeter, observation, priorité).
 * Les droits de chaque rôle s'appliquent ensuite comme sur le site : ce filtre ne fait que retirer des possibilités.
 */
@Component
public class ApplicationMobileFilter extends OncePerRequestFilter {

    public static final String AGENT = "MBSCFinappAndroid";
    public static final String CODE = "APPLICATION_MOBILE_LECTURE_SEULE";
    static final String ROLE_CAISSIER = "ROLE_CAISSIER";
    static final String ROLE_COMPTABLE = "ROLE_COMPTABLE";
    static final String ROLE_DFIN = "ROLE_DFIN";
    static final String ROLE_DA = "ROLE_DA";
    /** Seuils de priorité des notes (réserve de trésorerie à préserver par priorité), fixés par le DA seul. */
    static final String SEUILS_PRIORITE = "/notes-frais/parametres-priorite";

    /** Décisions du circuit de validation d'une note de frais. */
    public static final Pattern DECISIONS_NOTE =
        Pattern.compile("^/notes-frais/\\d+/(verifier|transmettre|valider|rejeter|observation|priorite)$");

    /**
     * Décisions du circuit d'un budget (27/11/2026) : soumettre (comptable, DFIN), approuver et rejeter (DA), mettre en
     * exécution et clôturer (DFIN). L'élaboration (création, modification, révision, suppression) reste sur le site ;
     * BudgetService vérifie le rôle et le statut.
     */
    public static final Pattern DECISIONS_BUDGET =
        Pattern.compile("^/budgets/\\d+/(soumettre|approuver|rejeter|demarrer|cloturer)$");

    /**
     * Caissier (17/11/2026) : encaisser et décaisser en caisse, en banque et en mobile money (opérations, achats,
     * paiement des notes, retours de fonds), vendre (ventes, règlements, retours), tenir les fiches clients et
     * fournisseurs, créer et soumettre ses notes de frais (pièces jointes comprises ; sur une copie en ligne, par
     * {@code /actions-distantes/notes}). Banque et mobile money restent soumis à l'activation de leur module
     * (ModuleAccessFilter) ; ce que le rôle peut faire de chaque note reste décidé par NoteFraisService.
     */
    public static final Pattern SAISIES_CAISSIER = Pattern.compile(
        "^/(caisse|banque|mobile-money|ventes|clients|fournisseurs|notes-frais|actions-distantes/notes)(/.*)?$");

    /**
     * Comptable (17/11/2026) : créer ses notes de frais (décaissements), y joindre les pièces, les soumettre (sur une
     * copie en ligne, par {@code /actions-distantes/notes}), avec le contrôle budgétaire du formulaire.
     */
    public static final Pattern SAISIES_COMPTABLE = Pattern.compile("^/(notes-frais|actions-distantes/notes)(/.*)?$");

    /**
     * DFIN (17/11/2026) : pendant sa vérification, il corrige le libellé (objet), les comptes d'imputation et le
     * service budgétaire d'une note soumise ; NoteFraisService vérifie le rôle et le statut.
     */
    public static final Pattern CORRECTIONS_DFIN = Pattern.compile("^/notes-frais/\\d+/(objet|comptes|service)$");

    /** Suggestion du compte comptable par l'IA, demandée par les formulaires de caisse, de banque et de mobile money. */
    private static final String SUGGESTION_COMPTE = "/ia/suggestion-compte";

    private static final String MESSAGE = "L'application mobile sert à valider les notes de frais et les budgets, à consulter les "
        + "données et, pour le caissier, à encaisser, décaisser et vendre. Cette action se fait sur le site, depuis un "
        + "ordinateur.";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String agent = request.getHeader("User-Agent");
        if (agent == null || !agent.contains(AGENT)
            || estPermise(request.getMethod(), request.getServletPath(), aLeRole(ROLE_CAISSIER), aLeRole(ROLE_COMPTABLE),
                aLeRole(ROLE_DFIN), aLeRole(ROLE_DA))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Forbidden\",\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}");
    }

    /** Compte connecté (le filtre JWT est passé avant celui-ci) qui a ce rôle. */
    private static boolean aLeRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    static boolean estPermise(String methode, String chemin, boolean caissier) {
        return estPermise(methode, chemin, caissier, false, false, false);
    }

    static boolean estPermise(String methode, String chemin, boolean caissier, boolean comptable, boolean dfin,
                              boolean da) {
        if (methode.equals("GET") || methode.equals("HEAD") || methode.equals("OPTIONS")) return true;
        if (chemin.equals("/auth") || chemin.startsWith("/auth/")) return true;
        if (chemin.equals("/notifications") || chemin.startsWith("/notifications/")) return true;
        // Son propre compte : mot de passe (provisoire à remplacer au premier accès), double authentification, préférences.
        if (chemin.equals("/profil") || chemin.startsWith("/profil/")) return true;
        if (chemin.equals("/appareils") || chemin.startsWith("/appareils/")) return true;
        if (caissier && (SAISIES_CAISSIER.matcher(chemin).matches()
            || (methode.equals("POST") && chemin.equals(SUGGESTION_COMPTE)))) {
            return true;
        }
        if (comptable && SAISIES_COMPTABLE.matcher(chemin).matches()) return true;
        if (dfin && methode.equals("PUT") && CORRECTIONS_DFIN.matcher(chemin).matches()) return true;
        if (da && methode.equals("PUT") && chemin.equals(SEUILS_PRIORITE)) return true;
        return methode.equals("POST") && (DECISIONS_NOTE.matcher(chemin).matches() || DECISIONS_BUDGET.matcher(chemin).matches());
    }
}

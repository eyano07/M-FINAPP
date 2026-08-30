package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.service.PermissionService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Verifie, pour chaque requete touchant un module metier desactivable, que
 * le module est actif et que l'utilisateur authentifie dispose au moins du
 * niveau requis (LECTURE en GET, ECRITURE pour le reste) : voir
 * {@link PermissionService}.
 *
 * <p>S'AJOUTE aux {@code @PreAuthorize} existants sur chaque action de
 * workflow (soumettre/valider/rejeter...), sans les remplacer : ce filtre
 * n'est qu'une porte grossiere au niveau du module (equivalent a la
 * navigation/aux pages visibles), une requete doit toujours satisfaire les
 * deux pour aboutir. Les routes hors modules configurables (notes de
 * frais, authentification, notifications, referentiels partages,
 * administration...) ne sont pas concernees et passent directement.</p>
 */
@Component
@RequiredArgsConstructor
public class ModuleAccessFilter extends OncePerRequestFilter {

    private final PermissionService permissionService;

    // Liste (pas Map.ofEntries : ordre d'iteration non garanti) triee par
    // longueur de prefixe decroissante, construite une fois. Necessaire
    // depuis l'ajout des prefixes /drh/* : plusieurs d'entre eux partagent
    // le meme prefixe court /drh, donc resoudreModule doit toujours tester
    // le prefixe le plus specifique en premier pour retomber sur le bon
    // sous-module. Aucun des prefixes actuels n'est prefixe d'un autre en
    // dehors de /drh, mais trier par construction elimine le risque pour
    // tout ajout futur plutot que de compter sur une convention.
    private static final List<Map.Entry<String, ModuleMetier>> PREFIXES = Stream.of(
        Map.entry("/caisse", ModuleMetier.CAISSE),
        Map.entry("/banque", ModuleMetier.BANQUE),
        Map.entry("/mobile-money", ModuleMetier.MOBILE_MONEY),
        Map.entry("/comptabilite", ModuleMetier.COMPTABILITE),
        Map.entry("/budgets", ModuleMetier.COMPTABILITE),
        Map.entry("/ventes", ModuleMetier.VENTES),
        Map.entry("/clients", ModuleMetier.VENTES),
        Map.entry("/logistique", ModuleMetier.LOGISTIQUE),
        Map.entry("/transport", ModuleMetier.TRANSPORT),
        Map.entry("/patrimoine", ModuleMetier.PATRIMOINE),
        // Restaurant : le module expose ses propres endpoints plutot que de
        // taper /logistique/*, sinon un RESP_RESTAURANT (qui n'a pas le module
        // LOGISTIQUE) recevrait un 403 sur sa propre carte.
        Map.entry("/restaurant", ModuleMetier.RESTAURANT),
        // DRH : chaque prefixe vise un sous-module feuille, jamais le
        // conteneur DRH lui-meme (aucun controleur ne s'y rattache).
        Map.entry("/drh/employes", ModuleMetier.DRH_PERSONNEL),
        Map.entry("/drh/parametres-paie", ModuleMetier.DRH_PAIE),
        Map.entry("/drh/bulletins", ModuleMetier.DRH_PAIE),
        Map.entry("/drh/presences", ModuleMetier.DRH_PRESENCES),
        Map.entry("/drh/agents-presence-manuelle", ModuleMetier.DRH_PRESENCES),
        Map.entry("/drh/sorties", ModuleMetier.DRH_PRESENCES),
        Map.entry("/drh/missions", ModuleMetier.DRH_MISSIONS),
        Map.entry("/drh/sites", ModuleMetier.DRH_MISSIONS),
        Map.entry("/drh/rotations", ModuleMetier.DRH_MISSIONS)
    ).sorted(Comparator.<Map.Entry<String, ModuleMetier>>comparingInt(e -> e.getKey().length()).reversed())
     .toList();

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        ModuleMetier module = resoudreModule(request.getServletPath());
        if (module == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Pas de session valide : la suite de la chaine repondra 401 comme d'habitude.
            filterChain.doFilter(request, response);
            return;
        }

        NiveauPermission requis = HttpMethod.GET.matches(request.getMethod())
            ? NiveauPermission.LECTURE
            : NiveauPermission.ECRITURE;

        if (!permissionService.autorise(module, requis)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                "{\"error\":\"Forbidden\",\"message\":\"Module " + module
                    + " desactive ou droits insuffisants pour ce role.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private ModuleMetier resoudreModule(String path) {
        for (var entree : PREFIXES) {
            if (path.equals(entree.getKey()) || path.startsWith(entree.getKey() + "/")) {
                return entree.getValue();
            }
        }
        return null;
    }
}

package com.mbsc.finapp.audit;

import com.mbsc.finapp.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trace chaque action qui modifie des données (POST, PUT, PATCH, DELETE) dans le journal d'audit : qui (identifiant,
 * e-mail, rôles), d'où (adresse IP), quoi (module, opération, ressource), quand, et le résultat (statut HTTP).
 * Placé après l'authentification et avant le contrôle d'accès aux modules : les refus (403) sont donc tracés aussi.
 * La consultation (GET) n'est jamais tracée.
 */
@Component
@RequiredArgsConstructor
public class AuditFilter extends OncePerRequestFilter {

    private final AuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuditService.aTracer(request.getMethod(), request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long debut = System.currentTimeMillis();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Throwable echec = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            echec = e;
            throw e;
        } finally {
            tracer(request, response, auth, echec, System.currentTimeMillis() - debut);
        }
    }

    private void tracer(HttpServletRequest req, HttpServletResponse res, Authentication auth, Throwable echec, long dureeMs) {
        int statut = echec != null ? 500 : res.getStatus();
        AuditService.Action action = AuditService.decrire(req.getMethod(), req.getServletPath());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ACTION");
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            m.put("utilisateurId", p.getDomainUser().getId());
            m.put("email", p.getUsername());
            m.put("roles", auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(a -> a.replaceFirst("^ROLE_", "")).sorted().collect(Collectors.toList()));
        } else {
            m.put("utilisateurId", null);
            m.put("email", null);
        }
        m.put("ip", AuditService.adresseIp(req));
        m.put("module", action.module());
        m.put("operation", action.operation());
        m.put("ressourceId", action.ressourceId());
        m.put("methode", req.getMethod());
        m.put("chemin", req.getServletPath());
        String requete = AuditService.requeteNettoyee(req.getQueryString());
        if (requete != null) m.put("requete", requete);
        m.put("statut", statut);
        m.put("reussi", statut < 400);
        m.put("dureeMs", dureeMs);
        String agent = req.getHeader("User-Agent");
        if (agent != null) m.put("agent", agent.length() > 160 ? agent.substring(0, 160) : agent);
        audit.enregistrer(m);
    }
}

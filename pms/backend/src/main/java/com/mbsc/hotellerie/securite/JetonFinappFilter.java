package com.mbsc.hotellerie.securite;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les comptes sont ceux de M-FINAPP : un jeton d'accès M-FINAPP (en-tête Authorization) est vérifié par M-FINAPP lui-même
 * ({@code GET /pms/moi}, appel signé), qui renvoie l'utilisateur, ses rôles et son niveau sur le module Hôtellerie.
 * La réponse est gardée une minute (clé : empreinte du jeton). Module coupé, compte désactivé ou niveau AUCUN : refus.
 */
@Component
public class JetonFinappFilter extends OncePerRequestFilter {

    private static final long DUREE_CACHE_MS = 60_000;

    private record Entree(Session session, long expire) { }

    private static final java.util.Set<String> OUVERTES = java.util.Set.of("/auth/login", "/auth/sso", "/auth/refresh", "/auth/logout",
        "/auth/mot-de-passe");

    private final FinappClient finapp;
    private final Map<String, Entree> cache = new ConcurrentHashMap<>();

    public JetonFinappFilter(FinappClient finapp) {
        this.finapp = finapp;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String entete = request.getHeader("Authorization");
        // Connexion, jetons et changement du mot de passe : relayés à M-FINAPP, qui les contrôle lui-même (un mot de passe
        // provisoire y est refusé partout ailleurs).
        if (entete == null || !entete.startsWith("Bearer ") || OUVERTES.contains(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        String jeton = entete.substring(7).trim();
        String cle = empreinte(jeton);
        long maintenant = System.currentTimeMillis();
        Entree e = cache.get(cle);
        if (e == null || e.expire() < maintenant) {
            try {
                e = new Entree(lire(finapp.signe("GET", "/pms/moi", null, jeton), jeton), maintenant + DUREE_CACHE_MS);
            } catch (FinappException ex) {
                refuser(response, ex.statut() == 0 ? 503 : (ex.statut() == 403 ? 403 : 401), ex.getMessage(), ex.code());
                return;
            }
            if (cache.size() > 2000) cache.entrySet().removeIf(x -> x.getValue().expire() < maintenant);
            cache.put(cle, e);
        }
        Session s = e.session();
        if ("AUCUN".equals(s.niveau())) {
            refuser(response, 403, "Le module Hôtellerie est désactivé dans M-FINAPP, ou votre rôle n'y a pas accès.", null);
            return;
        }
        Set<SimpleGrantedAuthority> autorites = new HashSet<>();
        s.roles().forEach(r -> autorites.add(new SimpleGrantedAuthority("ROLE_" + r)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(s, null, autorites));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Oublie une session (déconnexion). */
    public void oublier(String jeton) {
        cache.remove(empreinte(jeton));
    }

    private static Session lire(JsonNode moi, String jeton) {
        Set<String> roles = new HashSet<>();
        moi.path("roles").forEach(r -> roles.add(r.asText()));
        return new Session(moi.path("id").asLong(), moi.path("email").asText(null), moi.path("nom").asText(null),
            moi.path("prenom").asText(null), Set.copyOf(roles), moi.path("niveauHotellerie").asText("AUCUN"), jeton);
    }

    private static String empreinte(String jeton) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jeton.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void refuser(HttpServletResponse response, int statut, String message, String code) throws IOException {
        response.setStatus(statut);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\""
            + (code == null ? "" : ",\"code\":\"" + code.replaceAll("[^A-Z_]", "") + "\"") + "}");
    }
}

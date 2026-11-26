package com.mbsc.finapp.security;

import com.mbsc.finapp.service.hotellerie.IntegrationPmsService;
import com.mbsc.finapp.service.instance.SignatureReplication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Appels de l'application Hôtellerie ({@code /pms/**}, V152) : acceptés seulement s'ils sont signés avec la clé
 * d'appairage du PMS (mêmes règles que {@link CleReplicationFilter} : identifiant d'appairage, horodatage dans la
 * fenêtre, nonce jamais vu, signature exacte du corps).
 *
 * <ul>
 *   <li>{@code /pms/systeme/**} (état, échange d'un code de connexion unique) : sans utilisateur, l'appel est authentifié
 *       comme « hôtellerie » (rôle {@value #ROLE}) ;</li>
 *   <li>tout autre {@code /pms/**} porte EN PLUS le jeton d'accès de l'utilisateur réel (en-tête Authorization), lu
 *       ensuite par {@link JwtAuthenticationFilter} : les droits, l'audit et la séparation des tâches restent
 *       nominatifs. La signature prouve seulement que l'appel vient du PMS appairé.</li>
 * </ul>
 */
@Component
public class ClePmsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClePmsFilter.class);
    public static final String PREFIXE = "/pms/";
    public static final String PREFIXE_SYSTEME = "/pms/systeme/";
    public static final String ROLE = "ROLE_PMS";
    public static final String CODE = "PMS_REFUSE";
    public static final String EN_TETE_APPAIRAGE = "X-Pms-Appairage";
    public static final String EN_TETE_HORODATAGE = "X-Pms-Horodatage";
    public static final String EN_TETE_NONCE = "X-Pms-Nonce";
    public static final String EN_TETE_SIGNATURE = "X-Pms-Signature";
    private static final int CORPS_MAX = 20 * 1024 * 1024;

    private final IntegrationPmsService integration;
    private final Map<String, Long> noncesVus = new ConcurrentHashMap<>();

    public ClePmsFilter(IntegrationPmsService integration) {
        this.integration = integration;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith(PREFIXE);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        var cle = integration.cle();
        if (cle.isEmpty()) {
            refuser(response, HttpServletResponse.SC_FORBIDDEN, "Aucune clé d'appairage de l'hôtellerie n'est enregistrée : "
                + "générez-la dans Administration › Hôtellerie.");
            return;
        }
        String appairage = request.getHeader(EN_TETE_APPAIRAGE);
        String horodatageTexte = request.getHeader(EN_TETE_HORODATAGE);
        String nonce = request.getHeader(EN_TETE_NONCE);
        String signature = request.getHeader(EN_TETE_SIGNATURE);
        if (appairage == null || horodatageTexte == null || nonce == null || signature == null) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Appel non signé.");
            return;
        }
        if (!appairage.equals(cle.get().appairageId())) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Clé d'appairage périmée : recopiez dans l'hôtellerie la clé affichée par M-FINAPP.");
            return;
        }
        long horodatage;
        try {
            horodatage = Long.parseLong(horodatageTexte);
        } catch (NumberFormatException e) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Horodatage illisible.");
            return;
        }
        long maintenant = System.currentTimeMillis();
        long fenetre = SignatureReplication.FENETRE.toMillis();
        if (Math.abs(maintenant - horodatage) > fenetre) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Horloges décalées de plus de 5 minutes entre l'hôtellerie et M-FINAPP.");
            return;
        }
        byte[] corps = lireCorps(request);
        if (corps == null) {
            refuser(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Envoi trop volumineux.");
            return;
        }
        String chemin = request.getServletPath() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        if (!SignatureReplication.verifier(cle.get().secret(), appairage, horodatage, nonce, request.getMethod(), chemin,
            corps, signature)) {
            log.warn("Appel de l'hôtellerie refusé : signature invalide ({} {})", request.getMethod(), chemin);
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Signature invalide : vérifiez la clé d'appairage de l'hôtellerie.");
            return;
        }
        if (noncesVus.size() > 2000) {
            noncesVus.entrySet().removeIf(e -> Math.abs(maintenant - e.getValue()) > 2 * fenetre);
        }
        if (noncesVus.putIfAbsent(nonce, horodatage) != null) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Appel déjà reçu (rejeu refusé).");
            return;
        }
        integration.noterContact();
        boolean systeme = request.getServletPath().startsWith(PREFIXE_SYSTEME);
        if (systeme) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("hotellerie", null, List.of(new SimpleGrantedAuthority(ROLE))));
        }
        try {
            filterChain.doFilter(new CorpsLu(request, corps), response);
        } finally {
            if (systeme) SecurityContextHolder.clearContext();
        }
    }

    private static byte[] lireCorps(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > CORPS_MAX) return null;
        try (InputStream in = request.getInputStream()) {
            byte[] octets = in.readNBytes(CORPS_MAX + 1);
            return octets.length > CORPS_MAX ? null : octets;
        }
    }

    private static void refuser(HttpServletResponse response, int statut, String message) throws IOException {
        response.setStatus(statut);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + CODE + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    /** Requête dont le corps, déjà lu pour vérifier la signature, est relu depuis la mémoire. */
    private static final class CorpsLu extends HttpServletRequestWrapper {
        private final byte[] corps;

        CorpsLu(HttpServletRequest request, byte[] corps) {
            super(request);
            this.corps = corps;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(corps);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return corps.length;
        }

        @Override
        public long getContentLengthLong() {
            return corps.length;
        }
    }
}

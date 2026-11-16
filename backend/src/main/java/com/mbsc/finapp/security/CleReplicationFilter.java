package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.service.instance.InstanceService;
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
 * Appels entre serveurs ({@code /replication/**}) : acceptes seulement sur le serveur en ligne (modes EN_LIGNE et
 * MIROIR) et seulement s'ils sont signes avec la cle d'appairage enregistree (voir {@link SignatureReplication}) :
 * meme identifiant d'appairage, horodatage dans la fenetre, nonce jamais vu, signature exacte du corps. L'appel est
 * alors authentifie comme « serveur local » (role REPLICATION), sans aucun compte utilisateur.
 */
@Component
public class CleReplicationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CleReplicationFilter.class);
    public static final String PREFIXE = "/replication/";
    public static final String ROLE = "ROLE_REPLICATION";
    /** Code renvoye quand l'appairage a ete revoque (« Reprendre la main ») : le serveur local doit s'arreter. */
    public static final String CODE_REVOQUE = "APPAIRAGE_REVOQUE";
    public static final String CODE_REFUS = "REPLICATION_REFUSEE";
    /** Taille maximale d'un corps signe (lots, resynchronisation complete compressee). */
    private static final int CORPS_MAX = 200 * 1024 * 1024;

    private final InstanceService instance;
    /** Nonces deja vus (valeur : horodatage) : un appel rejoue est refuse pendant toute la fenetre. */
    private final Map<String, Long> noncesVus = new ConcurrentHashMap<>();

    public CleReplicationFilter(InstanceService instance) {
        this.instance = instance;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getServletPath().startsWith(PREFIXE);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        ModeInstance mode = instance.mode();
        String appairageRecu = request.getHeader(SignatureReplication.EN_TETE_APPAIRAGE);
        if (appairageRecu != null && appairageRecu.equals(instance.etat().getAppairageRevoque())) {
            // « Reprendre la main » : ce serveur local n'est plus le principal, il doit cesser toute saisie.
            refuser(response, HttpServletResponse.SC_GONE, CODE_REVOQUE, "Le serveur en ligne a repris la main : "
                + "ce serveur local n'est plus le serveur principal.");
            return;
        }
        if (mode != ModeInstance.EN_LIGNE && mode != ModeInstance.MIROIR) {
            refuser(response, HttpServletResponse.SC_FORBIDDEN, "Ce serveur n'accepte pas de synchronisation (mode " + mode + ").");
            return;
        }
        var cle = instance.cle();
        if (cle.isEmpty()) {
            refuser(response, HttpServletResponse.SC_FORBIDDEN, "Aucune clé d'appairage n'est enregistrée sur le serveur en ligne.");
            return;
        }
        String appairage = request.getHeader(SignatureReplication.EN_TETE_APPAIRAGE);
        String horodatageTexte = request.getHeader(SignatureReplication.EN_TETE_HORODATAGE);
        String nonce = request.getHeader(SignatureReplication.EN_TETE_NONCE);
        String signature = request.getHeader(SignatureReplication.EN_TETE_SIGNATURE);
        if (appairage == null || horodatageTexte == null || nonce == null || signature == null) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Appel non signé.");
            return;
        }
        if (!appairage.equals(cle.get().appairageId())) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Appairage inconnu : la clé utilisée par le serveur local n'est plus celle du serveur en ligne.");
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
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Horloges décalées de plus de 5 minutes entre le serveur local "
                + "et le serveur en ligne : remettez l'heure du serveur local à l'heure.");
            return;
        }
        byte[] corps = lireCorps(request);
        if (corps == null) {
            refuser(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Envoi trop volumineux.");
            return;
        }
        String chemin = request.getServletPath() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        if (!SignatureReplication.verifier(cle.get().secret(), appairage, horodatage, nonce, request.getMethod(), chemin, corps,
            signature)) {
            log.warn("Appel de synchronisation refuse : signature invalide ({} {})", request.getMethod(), chemin);
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Signature invalide : vérifiez la clé d'appairage du serveur local.");
            return;
        }
        purgerNonces(maintenant, fenetre);
        if (noncesVus.putIfAbsent(nonce, horodatage) != null) {
            refuser(response, HttpServletResponse.SC_UNAUTHORIZED, "Appel déjà reçu (rejeu refusé).");
            return;
        }
        instance.noterContact();
        var auth = new UsernamePasswordAuthenticationToken("serveur-local", null, List.of(new SimpleGrantedAuthority(ROLE)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            filterChain.doFilter(new CorpsLu(request, corps), response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void purgerNonces(long maintenant, long fenetre) {
        if (noncesVus.size() > 1000) {
            noncesVus.entrySet().removeIf(e -> Math.abs(maintenant - e.getValue()) > 2 * fenetre);
        }
    }

    /** Corps de la requete en entier ; {@code null} s'il depasse la taille maximale. */
    private static byte[] lireCorps(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > CORPS_MAX) return null;
        try (InputStream in = request.getInputStream()) {
            byte[] octets = in.readNBytes(CORPS_MAX + 1);
            return octets.length > CORPS_MAX ? null : octets;
        }
    }

    private static void refuser(HttpServletResponse response, int statut, String message) throws IOException {
        refuser(response, statut, CODE_REFUS, message);
    }

    private static void refuser(HttpServletResponse response, int statut, String code, String message) throws IOException {
        response.setStatus(statut);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    /** Requete dont le corps, deja lu pour verifier la signature, est relu depuis la memoire. */
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

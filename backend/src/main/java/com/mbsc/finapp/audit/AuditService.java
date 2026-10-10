package com.mbsc.finapp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Journal d'audit des actions des utilisateurs : une ligne JSON par action dans le fichier {@code audit.log}
 * (logger « AUDIT », voir {@code logback-spring.xml}), à conserver pour les audits futurs.
 *
 * <p>Seules les actions qui modifient quelque chose (création, modification, suppression, validation, paiement,
 * connexion...) sont tracées ; la simple consultation de données ne l'est pas. Le contenu des requêtes n'est
 * jamais écrit (mots de passe, clés d'API, données personnelles) : on garde qui, quand, d'où, quoi (module,
 * opération, identifiant de la ressource) et le résultat.</p>
 */
@Component
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PARAM_SENSIBLE = Pattern.compile("(?i)^(token|access_?token|refresh_?token|password|mot_?de_?passe|cle|secret|jeton|key)$");
    private static final List<String> EXCLUS = List.of("/auth", "/notifications", "/health", "/actuator");
    /** Taille maximale de l'en-tête User-Agent conservé (assez pour garder « Mobile », placé après le navigateur). */
    private static final int TAILLE_AGENT = 300;

    /** Action HTTP à tracer : modifie des données, hors rafraîchissement de jeton, notifications et supervision. */
    public static boolean aTracer(String methode, String chemin) {
        if (!(methode.equals("POST") || methode.equals("PUT") || methode.equals("PATCH") || methode.equals("DELETE"))) return false;
        for (String p : EXCLUS) {
            if (chemin.equals(p) || chemin.startsWith(p + "/")) return false;
        }
        return true;
    }

    /** Action décrite par la requête : module, opération, identifiant de la ressource. */
    public record Action(String module, String operation, String ressourceId) {}

    public static Action decrire(String methode, String chemin) {
        String[] seg = chemin.replaceAll("^/+|/+$", "").split("/");
        String module = seg.length == 0 || seg[0].isEmpty() ? "-" : seg[0];
        if ((module.equals("drh") || module.equals("admin")) && seg.length > 1) module = module + "/" + seg[1];
        String id = null;
        int posId = -1;
        for (int i = 0; i < seg.length; i++) {
            if (seg[i].matches("\\d+")) {
                id = seg[i];
                posId = i;
                break;
            }
        }
        String operation;
        if (posId >= 0 && posId < seg.length - 1 && !seg[seg.length - 1].matches("\\d+") && methode.equals("POST")) {
            operation = seg[seg.length - 1].toUpperCase().replace('-', '_');   // /ventes/5/valider -> VALIDER
        } else {
            operation = switch (methode) {
                case "POST" -> "CREER";
                case "PUT", "PATCH" -> "MODIFIER";
                case "DELETE" -> "SUPPRIMER";
                default -> methode;
            };
        }
        return new Action(module, operation, id);
    }

    /** Chaîne de requête sans les paramètres sensibles. */
    public static String requeteNettoyee(String query) {
        if (query == null || query.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        for (String p : query.split("&")) {
            String nom = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
            if (PARAM_SENSIBLE.matcher(nom).matches()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(p);
        }
        String s = sb.toString();
        if (s.isEmpty()) return null;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Écrit une ligne d'audit. Ne lève jamais d'exception : l'audit ne doit pas casser l'action de l'utilisateur. */
    public void enregistrer(Map<String, Object> champs) {
        try {
            Map<String, Object> ligne = new LinkedHashMap<>();
            ligne.put("horodatage", Instant.now().toString());
            ligne.putAll(champs);
            AUDIT.info(JSON.writeValueAsString(ligne));
        } catch (Exception e) {
            log.warn("Ecriture du journal d'audit impossible : {}", e.toString());
        }
    }

    /** Événement d'authentification (connexion réussie ou refusée, déconnexion). */
    public void authentification(String evenement, String email, Long utilisateurId, boolean reussi, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "AUTHENTIFICATION");
        m.put("operation", evenement);
        m.put("utilisateurId", utilisateurId);
        m.put("email", email);
        m.put("ip", adresseIpCourante());
        String agent = agentCourant();
        if (agent != null) m.put("agent", agent);
        m.put("reussi", reussi);
        if (detail != null) m.put("detail", detail);
        enregistrer(m);
    }

    public static String adresseIp(HttpServletRequest r) {
        String ip = r.getHeader("X-Real-IP");   // posé par nginx (écrase toute valeur fournie par le client)
        if (ip == null || ip.isBlank()) ip = r.getRemoteAddr();
        return ip;
    }

    /** En-tête User-Agent de la requête (tronqué), d'où l'écran d'audit déduit le terminal et le système ; null s'il est absent. */
    public static String agent(HttpServletRequest r) {
        String a = r.getHeader("User-Agent");
        if (a == null || a.isBlank()) return null;
        a = a.strip();
        return a.length() > TAILLE_AGENT ? a.substring(0, TAILLE_AGENT) : a;
    }

    private static String adresseIpCourante() {
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        return a instanceof ServletRequestAttributes s ? adresseIp(s.getRequest()) : null;
    }

    private static String agentCourant() {
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        return a instanceof ServletRequestAttributes s ? agent(s.getRequest()) : null;
    }
}

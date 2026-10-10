package com.mbsc.finapp.audit;

import java.util.List;
import java.util.Locale;

/**
 * Déduit le type de terminal (ordinateur, mobile, tablette) et le système d'exploitation de l'en-tête HTTP
 * {@code User-Agent} d'une requête, pour le journal d'audit.
 *
 * <p>Cette information est déclarée par le navigateur : elle est indicative, pas une preuve (l'utilisateur peut la
 * modifier). Limites connues : Windows 10 et 11 sont indiscernables (même jeton « Windows NT 10.0 »), Chrome fige la
 * version d'Android dans son en-tête (seule la famille du système est donc donnée) et un iPad réglé sur « site pour
 * ordinateur » se présente comme un Mac.</p>
 */
public final class AgentUtilisateur {

    public static final String INCONNU = "Inconnu";
    public static final String ORDINATEUR = "Ordinateur";
    public static final String MOBILE = "Mobile";
    public static final String TABLETTE = "Tablette";
    /** Client qui n'est pas un navigateur : application de bureau, script, outil d'API. */
    public static final String APPLICATION = "Application";

    /** Type de terminal et système d'exploitation ; {@link #INCONNU} quand l'en-tête est absent ou non reconnu. */
    public record Terminal(String type, String systeme) {}

    private static final Terminal NON_RECONNU = new Terminal(INCONNU, INCONNU);
    private static final Terminal APPLICATION_TIERCE = new Terminal(APPLICATION, INCONNU);

    /** Débuts d'en-tête des clients qui ne sont pas des navigateurs (ceux-ci commencent par « Mozilla/ » ou « Opera/ »). */
    private static final List<String> CLIENTS_HORS_NAVIGATEUR = List.of(
        "java", "apache-httpclient", "okhttp", "curl/", "wget/", "python", "postman", "insomnia", "axios", "node", "undici", "go-http-client");

    private AgentUtilisateur() {}

    public static Terminal analyser(String agent) {
        if (agent == null || agent.isBlank()) return NON_RECONNU;
        String a = agent.strip().toLowerCase(Locale.ROOT);
        for (String debut : CLIENTS_HORS_NAVIGATEUR) {
            if (a.startsWith(debut)) return APPLICATION_TIERCE;
        }
        boolean mobi = a.contains("mobi");           // « Mobile » (Chrome, Safari, Firefox) ou « Mobi »
        boolean tablette = a.contains("tablet");
        // L'ordre compte : les en-têtes d'iPhone et d'iPad contiennent « Mac OS X », ceux d'Android contiennent « Linux ».
        if (a.contains("windows phone") || a.contains("iemobile")) return new Terminal(MOBILE, "Windows Phone");
        if (a.contains("ipad")) return new Terminal(TABLETTE, "iPadOS");
        if (a.contains("iphone") || a.contains("ipod")) return new Terminal(MOBILE, "iOS");
        if (a.contains("android")) return new Terminal(mobi && !tablette ? MOBILE : TABLETTE, "Android");   // Android sans « Mobile » : tablette
        if (a.contains(" cros ")) return new Terminal(ORDINATEUR, "ChromeOS");
        if (a.contains("windows")) return new Terminal(tablette ? TABLETTE : ORDINATEUR, "Windows");
        if (a.contains("macintosh") || a.contains("mac os x")) return new Terminal(ORDINATEUR, "macOS");
        if (a.contains("linux") || a.contains("x11")) return new Terminal(mobi ? MOBILE : ORDINATEUR, "Linux");
        return mobi ? new Terminal(MOBILE, INCONNU) : NON_RECONNU;
    }
}

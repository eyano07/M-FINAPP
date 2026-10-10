package com.mbsc.finapp.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** En-têtes User-Agent réels de navigateurs courants : type de terminal et système déduits. */
class AgentUtilisateurTest {

    private static void verifier(String agent, String type, String systeme) {
        AgentUtilisateur.Terminal t = AgentUtilisateur.analyser(agent);
        assertEquals(type, t.type(), "type pour : " + agent);
        assertEquals(systeme, t.systeme(), "système pour : " + agent);
    }

    @Test
    void ordinateurs() {
        verifier("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Ordinateur", "Windows");
        verifier("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0", "Ordinateur", "Windows");
        verifier("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0", "Ordinateur", "Windows");
        verifier("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15", "Ordinateur", "macOS");
        verifier("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0", "Ordinateur", "Linux");
        verifier("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Ordinateur", "ChromeOS");
    }

    @Test
    void mobiles() {
        verifier("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36", "Mobile", "Android");
        verifier("Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0", "Mobile", "Android");
        verifier("Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/24.0 Chrome/117.0.0.0 Mobile Safari/537.36", "Mobile", "Android");
        verifier("Mozilla/5.0 (Linux; Android 13; SM-G991B Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36", "Mobile", "Android");
        // « Mac OS X » figure aussi dans l'en-tête d'un iPhone : il ne doit pas être pris pour un Mac.
        verifier("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1", "Mobile", "iOS");
        verifier("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/124.0.6367.88 Mobile/15E148 Safari/604.1", "Mobile", "iOS");
        verifier("Mozilla/5.0 (Windows Phone 10.0; Android 6.0.1; Microsoft; Lumia 950) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.116 Mobile Safari/537.36 Edge/15.15254", "Mobile", "Windows Phone");
    }

    @Test
    void tablettes() {
        // Android sans le mot « Mobile » : tablette.
        verifier("Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Tablette", "Android");
        verifier("Mozilla/5.0 (Android 13; Tablet; rv:125.0) Gecko/125.0 Firefox/125.0", "Tablette", "Android");
        // L'en-tête d'un iPad contient « Mobile » et « Mac OS X » : c'est bien une tablette.
        verifier("Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1", "Tablette", "iPadOS");
    }

    @Test
    void clientsQuiNeSontPasDesNavigateurs() {
        verifier("Java-http-client/21.0.4", "Application", "Inconnu");
        verifier("curl/8.5.0", "Application", "Inconnu");
        verifier("PostmanRuntime/7.37.0", "Application", "Inconnu");
        verifier("python-requests/2.31.0", "Application", "Inconnu");
        verifier("okhttp/4.12.0", "Application", "Inconnu");
    }

    @Test
    void enteteAbsentOuNonReconnu() {
        verifier(null, "Inconnu", "Inconnu");
        verifier("", "Inconnu", "Inconnu");
        verifier("   ", "Inconnu", "Inconnu");
        verifier("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", "Inconnu", "Inconnu");
        // « Microsoft » contient « cros » : ce n'est pas un Chromebook.
        verifier("Microsoft Office/16.0 (Windows NT 10.0; Microsoft Outlook 16.0.17328; Pro)", "Ordinateur", "Windows");
    }
}

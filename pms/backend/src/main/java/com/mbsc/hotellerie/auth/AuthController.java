package com.mbsc.hotellerie.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.securite.JetonFinappFilter;
import com.mbsc.hotellerie.securite.Session;
import com.mbsc.hotellerie.securite.SessionCourante;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Connexion : par le lien de M-FINAPP (code à usage unique, échangé par un appel signé) ou avec les identifiants
 * M-FINAPP (relayés tels quels à M-FINAPP, qui applique ses propres règles : double authentification, blocage, audit).
 * L'hôtellerie ne voit ni ne garde aucun mot de passe.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final FinappClient finapp;
    private final JetonFinappFilter jetons;
    private final Journal journal;

    public AuthController(FinappClient finapp, JetonFinappFilter jetons, Journal journal) {
        this.finapp = finapp;
        this.jetons = jetons;
        this.journal = journal;
    }

    public record CodeRequest(String code) { }

    @PostMapping("/sso")
    public JsonNode sso(@RequestBody CodeRequest req) {
        if (req.code() == null || req.code().isBlank()) throw Refus.invalide("Lien de connexion incomplet.");
        return finapp.signe("POST", "/pms/systeme/sso", Map.of("code", req.code()), null);
    }

    @PostMapping("/login")
    public JsonNode login(@RequestBody Map<String, Object> identifiants) {
        return finapp.simple("POST", "/auth/login", identifiants);
    }

    @PostMapping("/refresh")
    public JsonNode refresh(@RequestBody Map<String, Object> corps) {
        return finapp.simple("POST", "/auth/refresh", corps);
    }

    /** Mot de passe provisoire (ou changé) : relayé à M-FINAPP, qui ferme ensuite toutes les sessions de l'utilisateur. */
    @org.springframework.web.bind.annotation.PutMapping("/mot-de-passe")
    public Map<String, Boolean> changerMotDePasse(@RequestHeader(value = "Authorization", required = false) String entete,
                                                  @RequestBody Map<String, Object> corps) {
        if (entete == null || !entete.startsWith("Bearer ")) throw new Refus(401, "Connectez-vous.");
        String jeton = entete.substring(7).trim();
        finapp.auNomDe("PUT", "/profil/mot-de-passe", corps, jeton);
        jetons.oublier(jeton);
        journal.noter("MOT_DE_PASSE_CHANGE", "compte M-FINAPP", "changé depuis l'hôtellerie");
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(@RequestHeader(value = "Authorization", required = false) String entete) {
        if (entete != null && entete.startsWith("Bearer ")) jetons.oublier(entete.substring(7).trim());
        return Map.of("ok", true);
    }

    /** L'utilisateur connecté, ses rôles et son niveau sur le module (route protégée). */
    @GetMapping("/moi")
    public Session moi() {
        Session s = SessionCourante.exiger();
        return new Session(s.id(), s.email(), s.nom(), s.prenom(), s.roles(), s.niveau(), null);
    }
}

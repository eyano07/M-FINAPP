package com.mbsc.hotellerie.appairage;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.finapp.Signature;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Santé (interrogée par M-FINAPP pour tester la connexion) et appairage. Tant que l'hôtellerie n'est pas appairée,
 * n'importe qui peut coller la clé (premier lancement) ; ensuite, seuls l'administrateur et la direction de
 * l'hébergement la remplacent.
 */
@RestController
public class AppairageController {

    private final AppairageService appairage;
    private final FinappClient finapp;
    private final Journal journal;
    private final String version;

    public AppairageController(AppairageService appairage, FinappClient finapp, Journal journal,
                               @Value("${app.version}") String version) {
        this.appairage = appairage;
        this.finapp = finapp;
        this.journal = journal;
        this.version = version;
    }

    @GetMapping("/sante")
    public Map<String, Object> sante() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("statut", "OK");
        r.put("application", "M-FINAPP Hôtellerie");
        r.put("version", version);
        r.put("appaire", appairage.appaire());
        return r;
    }

    /** État de la liaison avec M-FINAPP : appairée, joignable, module actif, nom de l'entreprise. */
    @GetMapping("/appairage")
    public Map<String, Object> etat() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("appaire", appairage.appaire());
        appairage.cle().ifPresent(c -> r.put("cleFin", c.fin()));
        if (appairage.appaire()) {
            try {
                JsonNode e = finapp.signe("GET", "/pms/systeme/etat", null, null);
                r.put("joignable", true);
                r.put("moduleActif", e.path("moduleActif").asBoolean());
                r.put("entreprise", e.path("entreprise").asText(null));
                r.put("deviseBase", e.path("deviseBase").asText("USD"));
            } catch (FinappException ex) {
                r.put("joignable", false);
                r.put("message", ex.getMessage());
            }
        }
        return r;
    }

    public record CleRequest(String cle) { }

    @PostMapping("/appairage")
    public Map<String, Object> appairer(@RequestBody CleRequest req) {
        String auteur = "premier lancement";
        if (appairage.appaire()) {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (!(a != null && a.getPrincipal() instanceof Session s && s.a(Roles.ADMIN, Roles.DIRECTION))) {
                throw new Refus(403, "L'hôtellerie est déjà appairée : seuls l'administrateur et la direction de l'hébergement "
                    + "peuvent changer la clé (connectez-vous).");
            }
            auteur = s.email();
        }
        Signature.Cle cle = appairage.enregistrer(req.cle(), auteur);
        try {
            finapp.signe("GET", "/pms/systeme/etat", null, null);
        } catch (FinappException ex) {
            journal.noter("APPAIRAGE", "clé …" + cle.fin(), "enregistrée, M-FINAPP refuse ou injoignable : " + ex.getMessage());
            throw new Refus(ex.statut() == 0 ? 503 : 400, "Clé enregistrée, mais M-FINAPP ne la reconnaît pas encore : "
                + ex.getMessage());
        }
        journal.noter("APPAIRAGE", "clé …" + cle.fin(), "clé d'appairage enregistrée et reconnue par M-FINAPP");
        return etat();
    }
}

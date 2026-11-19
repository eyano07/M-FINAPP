package com.mbsc.finapp.controller;

import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.hotellerie.IntegrationPmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Module Hôtellerie côté M-FINAPP (V152) : réglage de la connexion à l'application Hôtellerie (administrateur) et
 * connexion unique (tout utilisateur qui a le module). Le filtre de module coupe ces routes quand le module est coupé.
 */
@RestController
@RequestMapping("/hotellerie")
@RequiredArgsConstructor
public class HotellerieController {

    private final IntegrationPmsService integration;
    private final CurrentUserProvider currentUser;

    public record AdresseRequest(String adresse, Integer port) { }

    @GetMapping("/integration")
    @PreAuthorize("hasRole('ADMIN')")
    public IntegrationPmsService.Etat etat() {
        return integration.etat();
    }

    @PutMapping("/integration")
    @PreAuthorize("hasRole('ADMIN')")
    public IntegrationPmsService.Etat definir(@RequestBody AdresseRequest req) {
        return integration.definirAdresse(req.adresse(), req.port(), currentUser.requireUser());
    }

    /** Nouvelle clé d'appairage, affichée une seule fois : à coller dans l'écran de premier lancement de l'hôtellerie. */
    @PostMapping("/integration/cle")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> nouvelleCle() {
        return Map.of("cle", integration.nouvelleCle(currentUser.requireUser()));
    }

    @PostMapping("/integration/test")
    @PreAuthorize("hasRole('ADMIN')")
    public IntegrationPmsService.Test tester() {
        return integration.tester();
    }

    /** Adresse de l'hôtellerie pour le menu (sans secret) : null tant qu'elle n'est pas réglée. */
    @GetMapping("/lien")
    public Map<String, Object> lien() {
        var e = integration.etat();
        return java.util.Collections.singletonMap("url", e.cleDefinie() ? e.url() : null);
    }

    /** Connexion unique : adresse de l'hôtellerie avec un code à usage unique (60 s) au nom de l'utilisateur. */
    @PostMapping("/sso")
    public Map<String, String> sso() {
        var e = integration.etat();
        if (e.url() == null || !e.cleDefinie()) {
            throw new IllegalStateException("L'hôtellerie n'est pas encore reliée : l'administrateur doit saisir son adresse "
                + "et générer la clé d'appairage (Administration › Hôtellerie).");
        }
        String code = integration.emettreCode(currentUser.requireUserId());
        return Map.of("url", e.url() + "/sso?code=" + code);
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.auth.AuthResponse;
import com.mbsc.finapp.dto.hotellerie.PmsDtos;
import com.mbsc.finapp.dto.vente.VenteResponse;
import com.mbsc.finapp.service.AuthService;
import com.mbsc.finapp.service.hotellerie.IntegrationPmsService;
import com.mbsc.finapp.service.hotellerie.PmsIntegrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API de l'application Hôtellerie (V152). Chaque appel est signé avec la clé d'appairage (ClePmsFilter). Les routes
 * {@code /pms/systeme/**} n'ont pas d'utilisateur ; toutes les autres portent le jeton de l'utilisateur réel.
 */
@RestController
@RequestMapping("/pms")
@RequiredArgsConstructor
public class PmsIntegrationController {

    private final PmsIntegrationService service;
    private final IntegrationPmsService integration;
    private final AuthService auth;

    @GetMapping("/systeme/etat")
    @PreAuthorize("hasRole('PMS')")
    public PmsDtos.Etat etat() {
        return service.etat();
    }

    /** Code de connexion unique contre une session M-FINAPP (jetons d'accès et de rafraîchissement). */
    @PostMapping("/systeme/sso")
    @PreAuthorize("hasRole('PMS')")
    public AuthResponse sso(@Valid @RequestBody PmsDtos.EchangeCode req) {
        if (!service.etat().moduleActif()) {
            throw new BadCredentialsException("Le module Hôtellerie est désactivé dans M-FINAPP.");
        }
        Long userId = integration.consommerCode(req.code())
            .orElseThrow(() -> new BadCredentialsException("Lien de connexion expiré ou déjà utilisé : rouvrez l'hôtellerie depuis M-FINAPP."));
        return auth.ouvrirSessionUnique(userId);
    }

    /** E-mail à un client de l'hôtellerie (confirmation, rappel, code d'accès, remerciement), par la messagerie de M-FINAPP. */
    @PostMapping("/systeme/messages")
    @PreAuthorize("hasRole('PMS')")
    public PmsDtos.MessageEnvoye message(@Valid @RequestBody PmsDtos.Message m) {
        return service.envoyerMessage(m);
    }

    @GetMapping("/moi")
    public PmsDtos.Moi moi() {
        return service.moi();
    }

    @GetMapping("/referentiel")
    public PmsDtos.Referentiel referentiel() {
        return service.referentiel();
    }

    @GetMapping("/clients")
    public List<PmsDtos.Client> clients(@RequestParam(required = false) String q) {
        return service.clients(q);
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public PmsDtos.Client creerClient(@Valid @RequestBody PmsDtos.NouveauClient req) {
        return service.creerClient(req);
    }

    @PostMapping("/acomptes")
    public PmsDtos.Piece acompte(@Valid @RequestBody PmsDtos.Fonds req) {
        return service.acompte(req);
    }

    @PostMapping("/acomptes/remboursements")
    public PmsDtos.Piece rembourserAcompte(@Valid @RequestBody PmsDtos.Fonds req) {
        return service.rembourserAcompte(req);
    }

    @PostMapping("/cautions")
    public PmsDtos.Piece caution(@Valid @RequestBody PmsDtos.Fonds req) {
        return service.caution(req);
    }

    @PostMapping("/cautions/restitutions")
    public PmsDtos.Piece restituerCaution(@Valid @RequestBody PmsDtos.Fonds req) {
        return service.restituerCaution(req);
    }

    @PostMapping("/folios")
    public PmsDtos.Folio encaisserFolio(@Valid @RequestBody PmsDtos.EncaissementFolio req) {
        return service.encaisserFolio(req);
    }

    @PostMapping("/folios/{venteId}/reglement")
    public PmsDtos.Folio reglerFolio(@PathVariable Long venteId, @Valid @RequestBody PmsDtos.ReglementFolio req) {
        return service.reglerFolio(venteId, req);
    }

    @GetMapping("/ventes/{id}")
    public VenteResponse vente(@PathVariable Long id) {
        return service.vente(id);
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.vente.AjouterLigneVenteRequest;
import com.mbsc.finapp.dto.vente.ReglerCreanceRequest;
import com.mbsc.finapp.dto.vente.RemboursementRetourRequest;
import com.mbsc.finapp.dto.vente.RetirerLigneVenteRequest;
import com.mbsc.finapp.dto.vente.RetourVenteRequest;
import com.mbsc.finapp.dto.vente.RetourVenteResponse;
import com.mbsc.finapp.dto.vente.VenteRequest;
import com.mbsc.finapp.dto.vente.VenteResponse;
import com.mbsc.finapp.service.RetourVenteService;
import com.mbsc.finapp.service.VenteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST du module Vente. Le controle d'acces est porte par
 * {@link VenteService}.
 */
@RestController
@RequestMapping("/ventes")
@RequiredArgsConstructor
public class VenteController {

    private final VenteService service;
    private final RetourVenteService retours;

    @GetMapping
    public List<VenteResponse> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public VenteResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenteResponse creer(@Valid @RequestBody VenteRequest req) {
        return service.creer(req);
    }

    @PostMapping("/{id}/lignes")
    public VenteResponse ajouterLigne(@PathVariable Long id, @Valid @RequestBody AjouterLigneVenteRequest req) {
        return service.ajouterLigne(id, req);
    }

    @PostMapping("/{id}/valider")
    public VenteResponse valider(@PathVariable Long id) {
        return service.valider(id);
    }

    @PostMapping("/{id}/annuler")
    public VenteResponse annuler(@PathVariable Long id) {
        return service.annuler(id);
    }

    @PostMapping("/{id}/regler")
    public VenteResponse reglerCreance(@PathVariable Long id, @Valid @RequestBody ReglerCreanceRequest req) {
        return service.reglerCreance(id, req);
    }

    // ── Commandes en attente (V134) ──

    /** Retour d'un article commandé avant paiement : la ligne est retirée (ou sa quantité réduite), sa réservation libérée. */
    @PostMapping("/{id}/lignes/{ligneId}/retirer")
    public VenteResponse retirerLigne(@PathVariable Long id, @PathVariable Long ligneId,
                                      @Valid @RequestBody(required = false) RetirerLigneVenteRequest req) {
        return service.retirerLigne(id, ligneId, req == null ? null : req.quantite());
    }

    /** Commande en attente abandonnée : rien n'avait été comptabilisé, ce qu'elle réservait redevient disponible. */
    @PostMapping("/{id}/abandonner")
    public VenteResponse abandonner(@PathVariable Long id) {
        return service.abandonner(id);
    }

    // ── Retours d'une vente payée, avec remboursement (V134) ──

    @GetMapping("/{id}/retours")
    public List<RetourVenteResponse> retours(@PathVariable Long id) {
        return retours.listerParVente(id);
    }

    /** Le caissier demande le retour : le responsable du bar, de la cuisine ou du restaurant décidera. */
    @PostMapping("/{id}/retours")
    @ResponseStatus(HttpStatus.CREATED)
    public RetourVenteResponse demanderRetour(@PathVariable Long id, @Valid @RequestBody RetourVenteRequest req) {
        return retours.demander(id, req);
    }

    /** Remboursement d'un retour approuvé : extourne de la part rendue, remise en stock, vides repris. */
    @PostMapping("/retours/{retourId}/rembourser")
    public RetourVenteResponse rembourserRetour(@PathVariable Long retourId,
                                                @RequestBody(required = false) RemboursementRetourRequest req) {
        return retours.rembourser(retourId, req);
    }

    /** Le caissier retire une demande de retour pas encore remboursée. */
    @PostMapping("/retours/{retourId}/annuler")
    public RetourVenteResponse annulerRetour(@PathVariable Long retourId) {
        return retours.annuler(retourId);
    }
}

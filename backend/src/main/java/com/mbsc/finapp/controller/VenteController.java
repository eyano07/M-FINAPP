package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.vente.ReglerCreanceRequest;
import com.mbsc.finapp.dto.vente.VenteRequest;
import com.mbsc.finapp.dto.vente.VenteResponse;
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
}

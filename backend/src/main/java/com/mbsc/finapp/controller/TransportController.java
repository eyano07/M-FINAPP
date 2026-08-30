package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.transport.*;
import com.mbsc.finapp.service.TransportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transport")
@RequiredArgsConstructor
public class TransportController {

    private final TransportService service;

    // Véhicules
    @GetMapping("/vehicules")
    public List<VehiculeResponse> listerVehicules() {
        return service.listerVehicules();
    }

    @PostMapping("/vehicules")
    @ResponseStatus(HttpStatus.CREATED)
    public VehiculeResponse creerVehicule(@Valid @RequestBody VehiculeRequest req) {
        return service.creerVehicule(req);
    }

    @PutMapping("/vehicules/{id}")
    public VehiculeResponse modifierVehicule(@PathVariable Long id, @Valid @RequestBody VehiculeRequest req) {
        return service.modifierVehicule(id, req);
    }

    @GetMapping("/vehicules/{id}/couts")
    public CoutVehiculeResponse coutsVehicule(@PathVariable Long id) {
        return service.coutsVehicule(id);
    }

    // Trajets
    @GetMapping("/trajets")
    public List<TrajetResponse> listerTrajets() {
        return service.listerTrajets();
    }

    @PostMapping("/trajets")
    @ResponseStatus(HttpStatus.CREATED)
    public TrajetResponse creerTrajet(@Valid @RequestBody TrajetRequest req) {
        return service.creerTrajet(req);
    }

    @PutMapping("/trajets/{id}")
    public TrajetResponse modifierTrajet(@PathVariable Long id, @Valid @RequestBody TrajetRequest req) {
        return service.modifierTrajet(id, req);
    }

    // Dépenses
    @GetMapping("/depenses")
    public List<DepenseResponse> listerDepenses() {
        return service.listerDepenses();
    }

    @PostMapping("/depenses")
    @ResponseStatus(HttpStatus.CREATED)
    public DepenseResponse creerDepense(@Valid @RequestBody DepenseRequest req) {
        return service.creerDepense(req);
    }

    @PostMapping("/depenses/{id}/comptabiliser")
    public DepenseResponse comptabiliser(@PathVariable Long id) {
        return service.comptabiliser(id);
    }

    @PostMapping("/depenses/{id}/annuler-comptabilisation")
    public DepenseResponse annulerComptabilisation(@PathVariable Long id) {
        return service.annulerComptabilisation(id);
    }
}

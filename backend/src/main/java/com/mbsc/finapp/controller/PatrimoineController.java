package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.patrimoine.*;
import com.mbsc.finapp.service.PatrimoineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Module Patrimoine. Le prefixe /patrimoine est rattache au module
 * {@code ModuleMetier.PATRIMOINE} par {@code ModuleAccessFilter} : la lecture
 * exige LECTURE, toute autre methode exige ECRITURE.
 */
@RestController
@RequestMapping("/patrimoine")
@RequiredArgsConstructor
public class PatrimoineController {

    private final PatrimoineService service;
    private final com.mbsc.finapp.service.patrimoine.InventairePatrimoineService inventaire;

    /** Modification de la fiche d'amortissement par le responsable logistique (19/11/2026). */
    @org.springframework.web.bind.annotation.PutMapping("/immobilisations/{id}")
    public ImmobilisationResponse modifierFiche(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.dto.patrimoine.FicheImmobilisationRequest req) {
        return service.modifierFiche(id, req);
    }

    /** Inventaire du patrimoine (V153) : biens relevés, codes collés, valorisation. */
    @GetMapping("/inventaire")
    public List<com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien> inventaire() {
        return inventaire.lister();
    }

    @org.springframework.web.bind.annotation.PutMapping("/inventaire/{id}")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien corriger(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.CorrectionRequest req) {
        return inventaire.corriger(id, req);
    }

    @PostMapping("/inventaire/{id}/valorisation")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien valoriser(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.ValorisationRequest req) {
        return inventaire.valoriser(id, req);
    }

    @GetMapping("/immobilisations")
    public List<ImmobilisationResponse> lister() {
        return service.lister();
    }

    @GetMapping("/immobilisations/{id}")
    public ImmobilisationResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @GetMapping("/immobilisations/{id}/amortissements")
    public List<LigneAmortissementResponse> planAmortissement(@PathVariable Long id) {
        return service.planAmortissement(id);
    }

    @PostMapping("/immobilisations")
    @ResponseStatus(HttpStatus.CREATED)
    public ImmobilisationResponse creer(@Valid @RequestBody ImmobilisationRequest req) {
        return service.creer(req);
    }

    @PostMapping("/immobilisations/{id}/sortie")
    public ImmobilisationResponse sortir(@PathVariable Long id, @Valid @RequestBody SortieRequest req) {
        return service.sortir(id, req);
    }

    /** Passe les dotations echues jusqu'a la date demandee (action volontaire). */
    @PostMapping("/amortissements/comptabiliser")
    public DotationsResponse comptabiliserDotations(@Valid @RequestBody DotationsRequest req) {
        return service.comptabiliserDotations(req.jusqua());
    }
}

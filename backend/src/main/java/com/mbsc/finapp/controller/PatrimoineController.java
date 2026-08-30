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

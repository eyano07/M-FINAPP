package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.SortieTravailleurRequest;
import com.mbsc.finapp.dto.drh.SortieTravailleurResponse;
import com.mbsc.finapp.service.SortieTravailleurService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sorties de travailleurs (module DRH_PRESENCES). Le préfixe /drh/sorties
 * est rattaché à {@code ModuleMetier.DRH_PRESENCES} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/sorties")
@RequiredArgsConstructor
public class SortieTravailleurController {

    private final SortieTravailleurService service;

    @GetMapping
    public List<SortieTravailleurResponse> lister(@RequestParam(required = false) Integer mois,
                                                   @RequestParam(required = false) Integer annee) {
        return service.listerPeriode(mois, annee);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SortieTravailleurResponse creer(@Valid @RequestBody SortieTravailleurRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public SortieTravailleurResponse modifier(@PathVariable Long id, @Valid @RequestBody SortieTravailleurRequest req) {
        return service.modifier(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}

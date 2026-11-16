package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.RegularisationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Régularisations de fin d'exercice : CCA, PCA, FNP, FAE et leur extourne d'ouverture (audit SYSCOHADA, C-11). */
@RestController
@RequestMapping("/comptabilite/regularisations")
@RequiredArgsConstructor
public class RegularisationController {

    private final RegularisationService service;

    @GetMapping
    public List<RegularisationService.Regularisation> lister(@RequestParam(required = false) Integer annee) {
        return service.lister(annee);
    }

    @PostMapping
    public RegularisationService.Regularisation creer(@RequestBody RegularisationService.Requete req) {
        return service.creer(req);
    }

    @PostMapping("/{id}/annuler")
    public void annuler(@PathVariable Long id) {
        service.annuler(id);
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.ProvisionCongesService;
import com.mbsc.finapp.service.ProvisionCongesService.ProvisionResume;
import com.mbsc.finapp.service.ProvisionCongesService.Simulation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Provision pour congés payés de fin d'exercice (module DRH_PAIE ; audit DRH du 15/11/2026, D-05). Le préfixe
 * /drh/provisions-conges est rattaché à {@code ModuleMetier.DRH_PAIE} par {@code ModuleAccessFilter}. Simulation et
 * liste : RESP_DRH, DFIN, ADMIN ; constitution et annulation : DFIN seul (voir {@link ProvisionCongesService}).
 */
@RestController
@RequestMapping("/drh/provisions-conges")
@RequiredArgsConstructor
public class ProvisionCongesController {

    private final ProvisionCongesService service;

    @GetMapping("/simulation")
    public Simulation simuler(@RequestParam Integer annee,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                              LocalDate dateArrete) {
        return service.simuler(annee, dateArrete);
    }

    @GetMapping
    public List<ProvisionResume> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionResume constituer(@RequestParam Integer annee,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate dateArrete) {
        return service.constituer(annee, dateArrete);
    }

    @PostMapping("/{id}/annuler")
    public ProvisionResume annuler(@PathVariable Long id) {
        return service.annuler(id);
    }
}

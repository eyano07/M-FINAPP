package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.RotationSuperviseurRequest;
import com.mbsc.finapp.dto.drh.RotationSuperviseurResponse;
import com.mbsc.finapp.service.RotationSuperviseurService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rotations de superviseurs (module DRH_MISSIONS). Le préfixe
 * /drh/rotations est rattaché à {@code ModuleMetier.DRH_MISSIONS} par
 * {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/rotations")
@RequiredArgsConstructor
public class RotationSuperviseurController {

    private final RotationSuperviseurService service;

    @GetMapping
    public List<RotationSuperviseurResponse> lister(@RequestParam(required = false) Integer annee,
                                                      @RequestParam(required = false) Integer mois) {
        return (annee != null && mois != null) ? service.listerParPeriode(annee, mois) : service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RotationSuperviseurResponse creer(@Valid @RequestBody RotationSuperviseurRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public RotationSuperviseurResponse modifier(@PathVariable Long id,
                                                 @Valid @RequestBody RotationSuperviseurRequest req) {
        return service.modifier(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}

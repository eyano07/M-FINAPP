package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.ParametresPaieRequest;
import com.mbsc.finapp.dto.drh.ParametresPaieResponse;
import com.mbsc.finapp.service.ParametresPaieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Paramètres de paie (module DRH_PAIE). Le préfixe /drh/parametres-paie est
 * rattaché à {@code ModuleMetier.DRH_PAIE} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/parametres-paie")
@RequiredArgsConstructor
public class ParametresPaieController {

    private final ParametresPaieService service;

    @GetMapping
    public ParametresPaieResponse consulter() {
        return service.consulter();
    }

    @PutMapping
    public ParametresPaieResponse enregistrer(@Valid @RequestBody ParametresPaieRequest req) {
        return service.enregistrer(req);
    }
}

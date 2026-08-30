package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.PresenceRequest;
import com.mbsc.finapp.dto.drh.PresenceResponse;
import com.mbsc.finapp.service.PresenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Pointage (module DRH_PRESENCES). Le préfixe /drh/presences est rattaché à
 * {@code ModuleMetier.DRH_PRESENCES} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/presences")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService service;

    @GetMapping
    public List<PresenceResponse> lister(@RequestParam Integer mois, @RequestParam Integer annee) {
        return service.listerPeriode(mois, annee);
    }

    @PutMapping
    public PresenceResponse enregistrer(@Valid @RequestBody PresenceRequest req) {
        return service.enregistrer(req);
    }

    @PostMapping("/tout-present")
    public List<PresenceResponse> marquerTousPresents(@RequestParam Integer mois, @RequestParam Integer annee) {
        return service.marquerTousPresents(mois, annee);
    }
}

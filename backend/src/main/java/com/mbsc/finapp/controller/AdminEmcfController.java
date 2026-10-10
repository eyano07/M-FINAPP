package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.emcf.EmcfEnregistrerRequest;
import com.mbsc.finapp.dto.emcf.EmcfEtatResponse;
import com.mbsc.finapp.dto.emcf.EmcfTestResponse;
import com.mbsc.finapp.service.emcf.AdminEmcfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Administration › Facture normalisée DGI : administrateur seul. */
@RestController
@RequestMapping("/admin/facturation-normalisee")
@RequiredArgsConstructor
public class AdminEmcfController {

    private final AdminEmcfService service;

    @GetMapping
    public EmcfEtatResponse etat() {
        return service.etat();
    }

    @PutMapping
    public EmcfEtatResponse enregistrer(@Valid @RequestBody EmcfEnregistrerRequest req) {
        return service.enregistrer(req);
    }

    @DeleteMapping("/jeton")
    public EmcfEtatResponse retirerJeton() {
        return service.retirerJeton();
    }

    @PostMapping("/tester")
    public EmcfTestResponse tester() {
        return service.tester();
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.ia.IaEnregistrerRequest;
import com.mbsc.finapp.dto.ia.IaEtatResponse;
import com.mbsc.finapp.dto.ia.IaModelesResponse;
import com.mbsc.finapp.dto.ia.IaTestResponse;
import com.mbsc.finapp.service.ia.AdminIaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Administration › Intelligence artificielle : clés OpenAI / Anthropic et modèles (administrateur seul). */
@RestController
@RequestMapping("/admin/ia")
@RequiredArgsConstructor
public class AdminIaController {

    private final AdminIaService service;

    @GetMapping
    public IaEtatResponse etat() {
        return service.etat();
    }

    @PutMapping("/{fournisseur}")
    public IaEtatResponse enregistrer(@PathVariable String fournisseur, @Valid @RequestBody IaEnregistrerRequest req) {
        return service.enregistrer(fournisseur, req);
    }

    @DeleteMapping("/{fournisseur}/cle")
    public IaEtatResponse retirerCle(@PathVariable String fournisseur) {
        return service.retirerCle(fournisseur);
    }

    @GetMapping("/{fournisseur}/modeles")
    public IaModelesResponse modeles(@PathVariable String fournisseur) {
        return service.modeles(fournisseur);
    }

    @PostMapping("/{fournisseur}/tester")
    public IaTestResponse tester(@PathVariable String fournisseur) {
        return service.tester(fournisseur);
    }
}

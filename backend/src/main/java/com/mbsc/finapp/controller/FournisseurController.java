package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.fournisseur.FournisseurRequest;
import com.mbsc.finapp.dto.fournisseur.FournisseurResponse;
import com.mbsc.finapp.service.FournisseurService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Répertoire des fournisseurs. Le contrôle d'accès est porté par {@link FournisseurService}. */
@RestController
@RequestMapping("/fournisseurs")
@RequiredArgsConstructor
public class FournisseurController {

    private final FournisseurService service;

    @GetMapping
    public List<FournisseurResponse> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FournisseurResponse creer(@Valid @RequestBody FournisseurRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public FournisseurResponse modifier(@PathVariable Long id, @Valid @RequestBody FournisseurRequest req) {
        return service.modifier(id, req);
    }

    @PostMapping("/{id}/archiver")
    public FournisseurResponse archiver(@PathVariable Long id) {
        return service.archiver(id);
    }

    @PostMapping("/{id}/reactiver")
    public FournisseurResponse reactiver(@PathVariable Long id) {
        return service.reactiver(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}

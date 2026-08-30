package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.provision.ProvisionRequest;
import com.mbsc.finapp.dto.provision.ProvisionResponse;
import com.mbsc.finapp.dto.provision.RepriseRequest;
import com.mbsc.finapp.service.ProvisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST des provisions. Le contrôle d'accès est porté par
 * {@link ProvisionService}.
 */
@RestController
@RequestMapping("/comptabilite/provisions")
@RequiredArgsConstructor
public class ProvisionController {

    private final ProvisionService service;

    @GetMapping
    public List<ProvisionResponse> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public ProvisionResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionResponse constituer(@Valid @RequestBody ProvisionRequest req) {
        return service.constituer(req);
    }

    @PostMapping("/{id}/reprendre")
    public ProvisionResponse reprendre(@PathVariable Long id, @Valid @RequestBody RepriseRequest req) {
        return service.reprendre(id, req);
    }
}

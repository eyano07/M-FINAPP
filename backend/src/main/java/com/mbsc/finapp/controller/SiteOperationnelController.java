package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.SiteOperationnelRequest;
import com.mbsc.finapp.dto.drh.SiteOperationnelResponse;
import com.mbsc.finapp.service.SiteOperationnelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sites opérationnels (module DRH_MISSIONS). Le préfixe /drh/sites est
 * rattaché à {@code ModuleMetier.DRH_MISSIONS} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/sites")
@RequiredArgsConstructor
public class SiteOperationnelController {

    private final SiteOperationnelService service;

    @GetMapping
    public List<SiteOperationnelResponse> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SiteOperationnelResponse creer(@Valid @RequestBody SiteOperationnelRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public SiteOperationnelResponse modifier(@PathVariable Long id, @Valid @RequestBody SiteOperationnelRequest req) {
        return service.modifier(id, req);
    }
}

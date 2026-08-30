package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.AgentPresenceManuelleRequest;
import com.mbsc.finapp.dto.drh.AgentPresenceManuelleResponse;
import com.mbsc.finapp.service.AgentPresenceManuelleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Fiche de présence manuelle (module DRH_PRESENCES). Le préfixe
 * /drh/agents-presence-manuelle est rattaché à
 * {@code ModuleMetier.DRH_PRESENCES} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/agents-presence-manuelle")
@RequiredArgsConstructor
public class AgentPresenceManuelleController {

    private final AgentPresenceManuelleService service;

    @GetMapping
    public List<AgentPresenceManuelleResponse> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentPresenceManuelleResponse ajouter(@Valid @RequestBody AgentPresenceManuelleRequest req) {
        return service.ajouter(req);
    }

    @PutMapping("/{id}")
    public AgentPresenceManuelleResponse modifier(@PathVariable Long id,
                                                   @Valid @RequestBody AgentPresenceManuelleRequest req) {
        return service.modifier(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirer(@PathVariable Long id) {
        service.retirer(id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void viderListe() {
        service.viderListe();
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.ia.SuggestionCompteRequest;
import com.mbsc.finapp.dto.ia.SuggestionCompteResponse;
import com.mbsc.finapp.service.IaAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent IA (ChatGPT) : assistance a la saisie comptable.
 * Voir {@link IaAssistantService} pour la logique et le mode degrade.
 */
@RestController
@RequestMapping("/ia")
@RequiredArgsConstructor
public class IaController {

    private final IaAssistantService ia;

    /**
     * Suggere le compte OHADA le plus adapte a partir d'un motif : compte de
     * charge pour un decaissement (defaut), compte de produit/passif pour
     * un encaissement (req.sens() = ENCAISSEMENT).
     */
    @PostMapping("/suggestion-compte")
    public SuggestionCompteResponse suggererCompte(@Valid @RequestBody SuggestionCompteRequest req) {
        return req.sens() == null
            ? ia.suggererCompte(req.description())
            : ia.suggererCompte(req.description(), req.sens());
    }
}

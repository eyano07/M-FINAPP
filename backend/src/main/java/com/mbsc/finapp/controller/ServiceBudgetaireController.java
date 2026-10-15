package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.budget.ServiceBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ServiceBudgetaireResponse;
import com.mbsc.finapp.service.ServiceBudgetaireService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Services (departements) auxquels se rattachent les lignes de depenses des budgets et les notes de frais. Hors du
 * prefixe /budgets (module BUDGET) : tout utilisateur qui saisit une note de frais doit pouvoir lire la liste pour
 * choisir le service de sa depense ; la gestion reste au DFIN et a l'administrateur (voir ServiceBudgetaireService).
 */
@RestController
@RequestMapping("/services-budgetaires")
@RequiredArgsConstructor
public class ServiceBudgetaireController {

    private final ServiceBudgetaireService service;

    @GetMapping
    public List<ServiceBudgetaireResponse> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceBudgetaireResponse creer(@Valid @RequestBody ServiceBudgetaireRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public ServiceBudgetaireResponse modifier(@PathVariable Long id, @Valid @RequestBody ServiceBudgetaireRequest req) {
        return service.modifier(id, req);
    }

    /** Active ou desactive le service. */
    @PatchMapping("/{id}/actif")
    public ServiceBudgetaireResponse basculerActif(@PathVariable Long id) {
        return service.basculerActif(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}

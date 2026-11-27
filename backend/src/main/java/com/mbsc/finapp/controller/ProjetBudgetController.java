package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.service.projet.ProjetBudgetService;
import com.mbsc.finapp.service.projet.ProjetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Projets à budgéter (V167), côté comptable et DFIN. Le préfixe /budgets rattache ces routes au module BUDGET.
 */
@RestController
@RequestMapping("/budgets/projets")
@RequiredArgsConstructor
public class ProjetBudgetController {

    private final ProjetBudgetService service;

    @GetMapping
    public List<ProjetService.Projet> aBudgeter() {
        return service.aBudgeter();
    }

    @GetMapping("/{id}")
    public ProjetService.Detail consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping("/{id}/retourner")
    public ProjetService.Projet retourner(@PathVariable Long id, @Valid @RequestBody ProjetBudgetService.RetourRequest r) {
        return service.retourner(id, r);
    }

    @PostMapping("/{id}/budget")
    public BudgetResponse creerBudget(@PathVariable Long id, @Valid @RequestBody ProjetBudgetService.CreationBudgetRequest r) {
        return service.creerBudget(id, r);
    }
}

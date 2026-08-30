package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST du module Budgets previsionnels (elaboration DFIN + approbation DA).
 */
@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService service;

    @GetMapping
    public List<BudgetResponse> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public BudgetResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse creer(@Valid @RequestBody BudgetRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public BudgetResponse modifier(@PathVariable Long id, @Valid @RequestBody BudgetRequest req) {
        return service.modifier(id, req);
    }

    @PostMapping("/{id}/soumettre")
    public BudgetResponse soumettre(@PathVariable Long id) {
        return service.soumettre(id);
    }

    @PostMapping("/{id}/approuver")
    public BudgetResponse approuver(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.approuver(id, action);
    }

    @PostMapping("/{id}/rejeter")
    public BudgetResponse rejeter(@PathVariable Long id,
                                  @Valid @RequestBody ActionWorkflowRequest action) {
        return service.rejeter(id, action);
    }

    @PostMapping("/{id}/demarrer")
    public BudgetResponse demarrer(@PathVariable Long id) {
        return service.demarrerExecution(id);
    }
}

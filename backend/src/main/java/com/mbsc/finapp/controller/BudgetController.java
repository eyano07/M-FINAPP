package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.budget.BudgetResumeResponse;
import com.mbsc.finapp.dto.budget.CompteBudgetableResponse;
import com.mbsc.finapp.dto.budget.PropositionBudgetRequest;
import com.mbsc.finapp.dto.budget.PropositionBudgetResponse;
import com.mbsc.finapp.dto.budget.RepartitionRequest;
import com.mbsc.finapp.dto.budget.RepartitionResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.service.BudgetPdfService;
import com.mbsc.finapp.service.BudgetService;
import com.mbsc.finapp.service.PropositionBudgetIaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST des budgets previsionnels : elaboration (DFIN), approbation (DA), execution et suivi (realise du
 * grand livre, engagements), proposition par l'IA, impression PDF.
 */
@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService service;
    private final PropositionBudgetIaService proposition;
    private final BudgetPdfService pdf;

    @GetMapping
    public List<BudgetResumeResponse> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public BudgetResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    /** Execution : prevu, realise et engage par ligne et par mois, totaux par section et nature, hors budget. */
    @GetMapping("/{id}/suivi")
    public SuiviBudgetResponse suivi(@PathVariable Long id) {
        return service.suivi(id);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        byte[] contenu = pdf.genererPdf(id);
        String reference = service.consulter(id).reference();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"budget-" + reference + ".pdf\"")
            .body(contenu);
    }

    /** Comptes budgetables (classes 2, 6, 7, 8) correspondant a une recherche par numero ou libelle. */
    @GetMapping("/comptes")
    public List<CompteBudgetableResponse> comptes(@RequestParam(required = false) String q) {
        return service.comptesBudgetables(q);
    }

    /** Ventilation d'un montant annuel sur douze mois (parts egales ou saisonnalite de l'annee precedente). */
    @PostMapping("/repartition")
    public RepartitionResponse repartir(@Valid @RequestBody RepartitionRequest req) {
        return service.repartir(req);
    }

    /** Budget annuel propose a partir des donnees reelles (IA, ou calcul local a defaut) ; rien n'est enregistre. */
    @PostMapping("/proposition")
    public PropositionBudgetResponse proposer(@Valid @RequestBody PropositionBudgetRequest req) {
        return proposition.proposer(req);
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
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

    @PostMapping("/{id}/reprendre")
    public BudgetResponse reprendre(@PathVariable Long id) {
        return service.reprendre(id);
    }

    @PostMapping("/{id}/demarrer")
    public BudgetResponse demarrer(@PathVariable Long id) {
        return service.demarrerExecution(id);
    }

    @PostMapping("/{id}/reviser")
    public BudgetResponse reviser(@PathVariable Long id) {
        return service.reviser(id);
    }

    @PostMapping("/{id}/cloturer")
    public BudgetResponse cloturer(@PathVariable Long id) {
        return service.cloturer(id);
    }
}

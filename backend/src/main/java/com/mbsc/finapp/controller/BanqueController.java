package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.banque.PayerNoteBancaireRequest;
import com.mbsc.finapp.dto.banque.TransactionBancaireRequest;
import com.mbsc.finapp.dto.banque.TransactionBancaireResponse;
import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.service.BanqueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * API REST du module Banque : operations, paiement des notes, journal.
 * Miroir de {@link CaisseController} pour le canal bancaire.
 */
@RestController
@RequestMapping("/banque")
@RequiredArgsConstructor
public class BanqueController {

    private final BanqueService service;

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionBancaireResponse enregistrer(@Valid @RequestBody TransactionBancaireRequest req) {
        return service.enregistrer(req);
    }

    @GetMapping("/transactions")
    public List<TransactionBancaireResponse> listerTransactions() {
        return service.listerTransactions();
    }

    @PostMapping("/notes/{noteId}/payer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionBancaireResponse payerNote(@PathVariable Long noteId,
                                                  @Valid @RequestBody PayerNoteBancaireRequest req) {
        return service.payerNote(noteId, req.etablissementId());
    }

    @GetMapping("/journal")
    public LivreJournalResponse journal(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.journal(du, au);
    }
}

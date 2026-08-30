package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.dto.mobilemoney.PayerNoteMobileMoneyRequest;
import com.mbsc.finapp.dto.mobilemoney.TransactionMobileMoneyRequest;
import com.mbsc.finapp.dto.mobilemoney.TransactionMobileMoneyResponse;
import com.mbsc.finapp.service.MobileMoneyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * API REST du module Mobile Money : operations, paiement des notes, journal.
 * Miroir de {@link CaisseController} pour le canal mobile money.
 */
@RestController
@RequestMapping("/mobile-money")
@RequiredArgsConstructor
public class MobileMoneyController {

    private final MobileMoneyService service;

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionMobileMoneyResponse enregistrer(@Valid @RequestBody TransactionMobileMoneyRequest req) {
        return service.enregistrer(req);
    }

    @GetMapping("/transactions")
    public List<TransactionMobileMoneyResponse> listerTransactions() {
        return service.listerTransactions();
    }

    @PostMapping("/notes/{noteId}/payer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionMobileMoneyResponse payerNote(@PathVariable Long noteId,
                                                     @Valid @RequestBody PayerNoteMobileMoneyRequest req) {
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

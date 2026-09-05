package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.caisse.AchatMarchandiseRequest;
import com.mbsc.finapp.dto.caisse.ReglementCamionsRequest;
import com.mbsc.finapp.dto.caisse.EcritureResponse;
import com.mbsc.finapp.dto.caisse.LigneBalanceResponse;
import com.mbsc.finapp.dto.caisse.TransactionCaisseRequest;
import com.mbsc.finapp.dto.caisse.TransactionCaisseResponse;
import com.mbsc.finapp.dto.comptabilite.LivreJournalResponse;
import com.mbsc.finapp.service.CaisseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * API REST du module Caisse : operations, paiement des notes, Grand Livre et balance.
 */
@RestController
@RequestMapping("/caisse")
@RequiredArgsConstructor
public class CaisseController {

    private final CaisseService service;

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCaisseResponse enregistrer(@Valid @RequestBody TransactionCaisseRequest req) {
        return service.enregistrer(req);
    }

    /**
     * Achat de marchandise regle en especes : genere le decaissement
     * (D 601 / C 571) ET l'entree en stock (D 311 / C 6031) — voir
     * {@code CaisseService.acheterMarchandise}.
     */
    @PostMapping("/achats")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCaisseResponse acheterMarchandise(@Valid @RequestBody AchatMarchandiseRequest req) {
        return service.acheterMarchandise(req);
    }

    /**
     * Reglement en especes des camions de minerais receptionnes
     * (D 4011 Fournisseurs / C 571 Caisse) — voir
     * {@code CaisseService.reglerCamionsMinerai}.
     */
    @PostMapping("/reglements-minerais")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCaisseResponse reglerCamionsMinerai(@Valid @RequestBody ReglementCamionsRequest req) {
        return service.reglerCamionsMinerai(req);
    }

    @GetMapping("/transactions")
    public List<TransactionCaisseResponse> listerTransactions() {
        return service.listerTransactions();
    }

    @PostMapping("/notes/{noteId}/payer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCaisseResponse payerNote(@PathVariable Long noteId) {
        return service.payerNote(noteId);
    }

    @PostMapping("/notes/{noteId}/encaisser")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionCaisseResponse encaisserNote(@PathVariable Long noteId) {
        return service.encaisserNote(noteId);
    }

    @GetMapping("/grand-livre")
    public List<EcritureResponse> grandLivre() {
        return service.grandLivre();
    }

    // L'endpoint POST /caisse/ecritures (écriture directe à une seule jambe)
    // a été retiré : il violait la partie double. Utiliser les pièces
    // comptables (POST /comptabilite/pieces) ou les opérations de caisse.

    @GetMapping("/balance")
    public List<LigneBalanceResponse> balance() {
        return service.balance();
    }

    @GetMapping("/journal")
    public LivreJournalResponse journal(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.journal(du, au);
    }
}

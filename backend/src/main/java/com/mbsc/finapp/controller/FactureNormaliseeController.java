package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.FactureNormalisee;
import com.mbsc.finapp.dto.emcf.FactureNormaliseeResponse;
import com.mbsc.finapp.repository.FactureNormaliseeRepository;
import com.mbsc.finapp.service.VentePdfService;
import com.mbsc.finapp.service.emcf.FactureNormaliseeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Facture normalisée d'une vente : état fiscal, retransmission, PDF. Soumis au module VENTES (préfixe /ventes). */
@RestController
@RequestMapping("/ventes")
@RequiredArgsConstructor
public class FactureNormaliseeController {

    private final FactureNormaliseeRepository repository;
    private final FactureNormaliseeService service;
    private final VentePdfService pdf;

    @GetMapping("/{id}/facture-normalisee")
    public List<FactureNormaliseeResponse> etat(@PathVariable Long id) {
        return service.etat(id);
    }

    @PostMapping("/{id}/facture-normalisee/retransmettre")
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    public List<FactureNormaliseeResponse> retransmettre(@PathVariable Long id) {
        service.retransmettreVente(id);
        return service.etat(id);
    }

    /** Factures en attente de certification ou refusées par le dispositif. */
    @GetMapping("/factures-a-regulariser")
    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'COMPTABLE', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<FactureNormaliseeResponse> aRegulariser() {
        return repository.aRegulariser().stream().map(FactureNormaliseeResponse::from).toList();
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(defaultValue = "VENTE") FactureNormalisee.Type type) {
        VentePdfService.Document d = pdf.generer(id, type);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.nomFichier()).build().toString())
            .contentType(MediaType.APPLICATION_PDF)
            .body(d.contenu());
    }
}

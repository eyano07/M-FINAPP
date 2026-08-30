package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.OrdreMissionRequest;
import com.mbsc.finapp.dto.drh.OrdreMissionResponse;
import com.mbsc.finapp.service.OrdreMissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ordres de mission (module DRH_MISSIONS). Le préfixe /drh/missions est
 * rattaché à {@code ModuleMetier.DRH_MISSIONS} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/missions")
@RequiredArgsConstructor
public class OrdreMissionController {

    private final OrdreMissionService service;

    @GetMapping
    public List<OrdreMissionResponse> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public OrdreMissionResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdreMissionResponse creer(@Valid @RequestBody OrdreMissionRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public OrdreMissionResponse modifier(@PathVariable Long id, @Valid @RequestBody OrdreMissionRequest req) {
        return service.modifier(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }

    /** Référentiel statique pour les listes déroulantes du formulaire. */
    @GetMapping("/provinces")
    public List<String> provinces() {
        return OrdreMissionService.PROVINCES;
    }

    @GetMapping("/moyens-transport")
    public List<String> moyensTransport() {
        return OrdreMissionService.MOYENS_TRANSPORT;
    }

    /** PDF sur papier entête MBSC (voir {@code OrdreMissionPdfService}). */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> telechargerPdf(@PathVariable Long id) {
        byte[] pdf = service.genererPdf(id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ordre-mission-" + id + ".pdf\"")
            .body(pdf);
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.CreerNoteReglementCamionsRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.dto.notes.NoteFraisResponse;
import com.mbsc.finapp.dto.notes.ModifierComptesRequest;
import com.mbsc.finapp.dto.notes.PrioriteRequest;
import com.mbsc.finapp.service.NoteFraisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * API REST du module Notes de frais (creation + workflow de validation).
 * Les controles de role sont portes par les @PreAuthorize du service.
 */
@RestController
@RequestMapping("/notes-frais")
@RequiredArgsConstructor
public class NoteFraisController {

    private final NoteFraisService service;

    @GetMapping
    public List<NoteFraisResponse> lister(@RequestParam(required = false) StatutNote statut) {
        return statut == null ? service.lister() : service.listerParStatut(statut);
    }

    @GetMapping("/{id}")
    public NoteFraisDetailResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteFraisDetailResponse creer(@Valid @RequestBody NoteFraisRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public NoteFraisDetailResponse modifier(@PathVariable Long id,
                                            @Valid @RequestBody NoteFraisRequest req) {
        return service.modifier(id, req);
    }

    /**
     * Cree et soumet directement au DFIN une note de reglement pour un ou
     * plusieurs camions de minerais valides par la caisse (LOGISTIQUE).
     */
    @PostMapping("/reglement-camions-minerai")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteFraisDetailResponse creerReglementCamionsMinerai(
            @Valid @RequestBody CreerNoteReglementCamionsRequest req) {
        return service.creerReglementCamionsMinerai(req);
    }

    /** Reaffectation des comptes d'imputation par le DFIN lors de la verification (note SOUMISE). */
    @PutMapping("/{id}/comptes")
    public NoteFraisDetailResponse modifierComptes(@PathVariable Long id,
                                                    @Valid @RequestBody ModifierComptesRequest req) {
        return service.modifierComptesLignes(id, req);
    }

    // --- Transitions de workflow -----------------------------------------

    @PostMapping("/{id}/soumettre")
    public NoteFraisDetailResponse soumettre(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.soumettre(id, action);
    }

    @PostMapping("/{id}/verifier")
    public NoteFraisDetailResponse verifier(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.verifier(id, action);
    }

    @PostMapping("/{id}/valider")
    public NoteFraisDetailResponse valider(@PathVariable Long id,
                                           @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.valider(id, action);
    }

    @PostMapping("/{id}/rejeter")
    public NoteFraisDetailResponse rejeter(@PathVariable Long id,
                                           @Valid @RequestBody ActionWorkflowRequest action) {
        return service.rejeter(id, action);
    }

    @PostMapping("/{id}/transmettre")
    public NoteFraisDetailResponse transmettre(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.transmettre(id, action);
    }

    @PostMapping("/{id}/priorite")
    public NoteFraisDetailResponse definirPriorite(@PathVariable Long id,
                                                   @Valid @RequestBody PrioriteRequest req) {
        return service.definirPriorite(id, req);
    }

    @PostMapping("/{id}/observation")
    public NoteFraisDetailResponse ajouterObservation(@PathVariable Long id,
                                                      @Valid @RequestBody ActionWorkflowRequest action) {
        return service.ajouterObservation(id, action);
    }

    @PostMapping("/{id}/annuler")
    public NoteFraisDetailResponse annuler(@PathVariable Long id,
                                           @Valid @RequestBody(required = false) ActionWorkflowRequest action) {
        return service.annuler(id, action);
    }

    // --- Pieces jointes (PDF / image) --------------------------------------

    @PostMapping(value = "/{id}/pieces-jointes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NoteFraisDetailResponse ajouterPieceJointe(@PathVariable Long id,
                                                      @RequestParam("fichier") MultipartFile fichier) {
        return service.ajouterPieceJointe(id, fichier);
    }

    @GetMapping("/{id}/pieces-jointes/{pieceId}")
    public ResponseEntity<org.springframework.core.io.Resource> telechargerPieceJointe(
            @PathVariable Long id, @PathVariable Long pieceId) {
        var telechargement = service.telechargerPieceJointe(id, pieceId);
        String nomEncode = java.net.URLEncoder.encode(telechargement.nomFichier(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        MediaType type = telechargement.typeMime() != null
            ? MediaType.parseMediaType(telechargement.typeMime())
            : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + nomEncode)
            .body(telechargement.ressource());
    }

    @DeleteMapping("/{id}/pieces-jointes/{pieceId}")
    public NoteFraisDetailResponse supprimerPieceJointe(@PathVariable Long id, @PathVariable Long pieceId) {
        return service.supprimerPieceJointe(id, pieceId);
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.rapprochement.ExtractionReleveResponse;
import com.mbsc.finapp.dto.rapprochement.LigneReleveRequest;
import com.mbsc.finapp.dto.rapprochement.PointageRequest;
import com.mbsc.finapp.dto.rapprochement.RapprochementsResponse;
import com.mbsc.finapp.dto.rapprochement.RegularisationRequest;
import com.mbsc.finapp.dto.rapprochement.ReleveDetailResponse;
import com.mbsc.finapp.dto.rapprochement.ReleveRequest;
import com.mbsc.finapp.service.ExtractionReleveIaService;
import com.mbsc.finapp.service.RapprochementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

/**
 * Rapprochement bancaire (module RAPPROCHEMENT, voir {@code RapprochementService}). Le préfixe
 * /rapprochements est rattaché au module par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/rapprochements")
@RequiredArgsConstructor
public class RapprochementController {

    private final RapprochementService service;
    private final ExtractionReleveIaService extraction;

    @GetMapping
    public RapprochementsResponse lister(@RequestParam Integer annee) {
        return service.lister(annee);
    }

    /** Lecture d'un relevé par l'IA (PDF, image, Excel, CSV) : rien n'est enregistré. */
    @PostMapping(value = "/extraction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionReleveResponse extraire(@RequestPart("fichier") MultipartFile fichier,
                                             @RequestParam(required = false) String devise) {
        return extraction.extraire(fichier, devise);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReleveDetailResponse creer(@Valid @RequestBody ReleveRequest req) {
        return service.creer(req);
    }

    @GetMapping("/{id}")
    public ReleveDetailResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PutMapping("/{id}/soldes")
    public ReleveDetailResponse modifierSoldes(@PathVariable Long id, @RequestParam(required = false) BigDecimal ouverture,
                                               @RequestParam(required = false) BigDecimal cloture) {
        return service.modifierSoldes(id, ouverture, cloture);
    }

    @PostMapping("/{id}/lignes")
    public ReleveDetailResponse ajouterLigne(@PathVariable Long id, @Valid @RequestBody LigneReleveRequest ligne) {
        return service.ajouterLigne(id, ligne);
    }

    @DeleteMapping("/{id}/lignes/{ligneId}")
    public ReleveDetailResponse supprimerLigne(@PathVariable Long id, @PathVariable Long ligneId) {
        return service.supprimerLigne(id, ligneId);
    }

    @PostMapping("/{id}/pointage-automatique")
    public ReleveDetailResponse pointerAutomatiquement(@PathVariable Long id) {
        return service.pointerAutomatiquement(id);
    }

    @PostMapping("/{id}/pointages")
    public ReleveDetailResponse pointer(@PathVariable Long id, @Valid @RequestBody PointageRequest req) {
        return service.pointer(id, req);
    }

    @DeleteMapping("/{id}/pointages/{pointageId}")
    public ReleveDetailResponse depointer(@PathVariable Long id, @PathVariable Long pointageId) {
        return service.depointer(id, pointageId);
    }

    @PostMapping("/{id}/lignes/{ligneId}/regularisation")
    public ReleveDetailResponse regulariser(@PathVariable Long id, @PathVariable Long ligneId,
                                            @Valid @RequestBody RegularisationRequest req) {
        return service.regulariser(id, ligneId, req);
    }

    @PostMapping("/{id}/valider")
    public ReleveDetailResponse valider(@PathVariable Long id) {
        return service.valider(id);
    }

    @PostMapping("/{id}/devalider")
    public ReleveDetailResponse devalider(@PathVariable Long id, @Valid @RequestBody ActionWorkflowRequest action) {
        return service.devalider(id, action.commentaire());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}

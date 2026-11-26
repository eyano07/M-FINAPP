package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.patrimoine.*;
import com.mbsc.finapp.service.PatrimoineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Module Patrimoine. Le prefixe /patrimoine est rattache au module
 * {@code ModuleMetier.PATRIMOINE} par {@code ModuleAccessFilter} : la lecture
 * exige LECTURE, toute autre methode exige ECRITURE.
 */
@RestController
@RequestMapping("/patrimoine")
@RequiredArgsConstructor
public class PatrimoineController {

    private final PatrimoineService service;
    private final com.mbsc.finapp.service.patrimoine.InventairePatrimoineService inventaire;
    private final com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService emplacements;
    private final com.mbsc.finapp.service.patrimoine.ConsommableService consommables;

    // ── Emplacements (affectations) et photos des biens (V154) ─────────────────

    @GetMapping("/emplacements")
    public List<com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.Emplacement> emplacements() {
        return emplacements.lister();
    }

    @GetMapping("/emplacements/unites-hotellerie")
    public List<com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.UniteHotellerie> unitesHotellerie() {
        return emplacements.unitesHotellerie();
    }

    @PostMapping("/emplacements")
    public com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.Emplacement creerEmplacement(
            @Valid @RequestBody com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.EmplacementRequest req) {
        return emplacements.enregistrer(null, req);
    }

    @PutMapping("/emplacements/{id}")
    public com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.Emplacement modifierEmplacement(@PathVariable Long id,
            @Valid @RequestBody com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.EmplacementRequest req) {
        return emplacements.enregistrer(id, req);
    }

    @DeleteMapping("/emplacements/{id}")
    public void supprimerEmplacement(@PathVariable Long id) {
        emplacements.supprimer(id);
    }

    @PostMapping("/emplacements/import-hotellerie")
    public com.mbsc.finapp.service.patrimoine.EmplacementPatrimoineService.Import importerUnites() {
        return emplacements.importerUnitesHotellerie();
    }

    @PutMapping("/inventaire/affectation")
    public List<com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien> affecter(
            @Valid @RequestBody com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.AffectationRequest req) {
        return inventaire.affecter(req);
    }

    @GetMapping("/inventaire/{id}/photos")
    public List<com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Photo> photos(@PathVariable Long id) {
        return inventaire.photos(id);
    }

    @PostMapping(value = "/inventaire/{id}/photos", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Photo ajouterPhoto(@PathVariable Long id,
            @RequestPart("fichier") org.springframework.web.multipart.MultipartFile fichier) {
        return inventaire.ajouterPhoto(id, fichier);
    }

    @GetMapping("/inventaire/{id}/photos/{photoId}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> photo(@PathVariable Long id, @PathVariable Long photoId) {
        var f = inventaire.fichierPhoto(id, photoId);
        if (f == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .contentType(org.springframework.http.MediaType.parseMediaType(f.typeContenu()))
            .body(f.ressource());
    }

    @DeleteMapping("/inventaire/{id}/photos/{photoId}")
    public void supprimerPhoto(@PathVariable Long id, @PathVariable Long photoId) {
        inventaire.supprimerPhoto(id, photoId);
    }

    // ── Facture d'acquisition d'un bien (V155) ─────────────────────────────

    @PostMapping(value = "/inventaire/{id}/facture", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien joindreFacture(@PathVariable Long id,
            @RequestPart("fichier") org.springframework.web.multipart.MultipartFile fichier) {
        return inventaire.joindreFacture(id, fichier);
    }

    @GetMapping("/inventaire/{id}/facture")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> facture(@PathVariable Long id) {
        var f = inventaire.fichierFacture(id);
        if (f == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
            .contentType(org.springframework.http.MediaType.parseMediaType(f.typeContenu()))
            .body(f.ressource());
    }

    @DeleteMapping("/inventaire/{id}/facture")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien retirerFacture(@PathVariable Long id) {
        return inventaire.retirerFacture(id);
    }

    /** Consommables simplifiés (V157) : création avec comptes automatiques, proforma, achat groupé en note de frais. */
    @GetMapping("/consommables")
    public List<com.mbsc.finapp.service.patrimoine.ConsommableService.Consommable> consommables() {
        return consommables.lister();
    }

    @PostMapping("/consommables")
    @ResponseStatus(HttpStatus.CREATED)
    public com.mbsc.finapp.service.patrimoine.ConsommableService.Consommable creerConsommable(
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.ConsommableService.ConsommableRequest req) {
        return consommables.creer(req);
    }

    @org.springframework.web.bind.annotation.PutMapping("/consommables/{id}")
    public com.mbsc.finapp.service.patrimoine.ConsommableService.Consommable modifierConsommable(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.ConsommableService.ConsommableRequest req) {
        return consommables.modifier(id, req);
    }

    @PostMapping(value = "/consommables/{id}/proforma", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.mbsc.finapp.service.patrimoine.ConsommableService.Consommable joindreProforma(@PathVariable Long id,
            @RequestPart("fichier") org.springframework.web.multipart.MultipartFile fichier) {
        return consommables.joindreProforma(id, fichier);
    }

    @GetMapping("/consommables/{id}/proforma")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> proforma(@PathVariable Long id) {
        var f = consommables.proforma(id);
        if (f == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
            .contentType(org.springframework.http.MediaType.parseMediaType(f.typeContenu()))
            .body(f.ressource());
    }

    @PostMapping("/consommables/achats")
    @ResponseStatus(HttpStatus.CREATED)
    public com.mbsc.finapp.service.patrimoine.ConsommableService.AchatResponse acheterConsommables(
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.ConsommableService.AchatRequest req) {
        return consommables.acheter(req);
    }

    /** Modification de la fiche d'amortissement par le responsable logistique (19/11/2026). */
    @org.springframework.web.bind.annotation.PutMapping("/immobilisations/{id}")
    public ImmobilisationResponse modifierFiche(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.dto.patrimoine.FicheImmobilisationRequest req) {
        return service.modifierFiche(id, req);
    }

    /** Inventaire du patrimoine (V153) : biens relevés, codes collés, valorisation. */
    @GetMapping("/inventaire")
    public List<com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien> inventaire() {
        return inventaire.lister();
    }

    @org.springframework.web.bind.annotation.PutMapping("/inventaire/{id}")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien corriger(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.CorrectionRequest req) {
        return inventaire.corriger(id, req);
    }

    @PostMapping("/inventaire/{id}/declassement")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien declasser(@PathVariable Long id) {
        return inventaire.declasser(id);
    }

    @PostMapping("/inventaire/{id}/valorisation")
    public com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.Bien valoriser(@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.mbsc.finapp.service.patrimoine.InventairePatrimoineService.ValorisationRequest req) {
        return inventaire.valoriser(id, req);
    }

    @GetMapping("/immobilisations")
    public List<ImmobilisationResponse> lister() {
        return service.lister();
    }

    @GetMapping("/immobilisations/{id}")
    public ImmobilisationResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @GetMapping("/immobilisations/{id}/amortissements")
    public List<LigneAmortissementResponse> planAmortissement(@PathVariable Long id) {
        return service.planAmortissement(id);
    }

    @PostMapping("/immobilisations")
    @ResponseStatus(HttpStatus.CREATED)
    public ImmobilisationResponse creer(@Valid @RequestBody ImmobilisationRequest req) {
        return service.creer(req);
    }

    @PostMapping("/immobilisations/{id}/sortie")
    public ImmobilisationResponse sortir(@PathVariable Long id, @Valid @RequestBody SortieRequest req) {
        return service.sortir(id, req);
    }

    /** Passe les dotations echues jusqu'a la date demandee (action volontaire). */
    @PostMapping("/amortissements/comptabiliser")
    public DotationsResponse comptabiliserDotations(@Valid @RequestBody DotationsRequest req) {
        return service.comptabiliserDotations(req.jusqua());
    }
}

package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.projet.ProjetExcelService;
import com.mbsc.finapp.service.projet.ProjetService;
import com.mbsc.finapp.service.projet.ProjetStockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Projets et fiche de stock projet (V167), côté logistique. Le préfixe /logistique rattache ces routes au module
 * LOGISTIQUE (lecture : consulter ; écriture : le logisticien).
 */
@RestController
@RequestMapping("/logistique/projets")
@RequiredArgsConstructor
public class ProjetController {

    private final ProjetService service;
    private final ProjetStockService stock;
    private final ProjetExcelService excel;

    @GetMapping
    public List<ProjetService.Projet> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjetService.Projet creer(@Valid @RequestBody ProjetService.ProjetRequest r) {
        return service.creer(r);
    }

    @GetMapping("/{id}")
    public ProjetService.Detail consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PutMapping("/{id}")
    public ProjetService.Projet modifier(@PathVariable Long id, @Valid @RequestBody ProjetService.ProjetRequest r) {
        return service.modifier(id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }

    @PutMapping("/{id}/besoins")
    public ProjetService.Detail besoins(@PathVariable Long id, @Valid @RequestBody ProjetService.BesoinsRequest r) {
        return service.enregistrerBesoins(id, r);
    }

    @PostMapping("/{id}/envoyer")
    public ProjetService.Projet envoyer(@PathVariable Long id) {
        return service.envoyer(id);
    }

    @PostMapping("/{id}/cloturer")
    public ProjetService.Projet cloturer(@PathVariable Long id) {
        return service.cloturer(id);
    }

    @PostMapping("/{id}/mouvements")
    public ProjetService.Detail mouvement(@PathVariable Long id, @Valid @RequestBody ProjetService.MouvementRequest r) {
        return service.mouvement(id, r);
    }

    @GetMapping("/a-affecter")
    public List<ProjetService.Article> aAffecter() {
        return service.aAffecter();
    }

    @PostMapping("/articles/{articleId}/affectation")
    public ProjetService.Article affecterArticle(@PathVariable Long articleId, @RequestBody ProjetService.AffectationRequest r) {
        return service.affecterArticle(articleId, r);
    }

    @GetMapping("/acquisitions-affectables")
    public List<ProjetService.Acquisition> acquisitionsAffectables() {
        return service.acquisitionsAffectables();
    }

    @PostMapping("/acquisitions/{immobilisationId}/affectation")
    public ProjetService.Acquisition affecterAcquisition(@PathVariable Long immobilisationId,
                                                        @RequestBody ProjetService.AffectationRequest r) {
        return service.affecterAcquisition(immobilisationId, r);
    }

    /** Rattrapage des achats « Projet … » payés et pas encore entrés. */
    @PostMapping("/synchroniser")
    public Map<String, Integer> synchroniser() {
        return Map.of("entrees", stock.synchroniser());
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exporter(@PathVariable Long id) {
        return classeur(excel.projet(id), "Projet_" + id + "_" + LocalDate.now() + ".xlsx");
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exporterTous() {
        return classeur(excel.tous(), "Projets_" + LocalDate.now() + ".xlsx");
    }

    private static ResponseEntity<byte[]> classeur(byte[] contenu, String nomFichier) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(nomFichier, StandardCharsets.UTF_8).build().toString())
            .body(contenu);
    }
}

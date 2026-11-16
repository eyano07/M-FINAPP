package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.InventaireService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Inventaires physiques des stocks et des immobilisations (audit SYSCOHADA, C-22). */
@RestController
@RequiredArgsConstructor
public class InventaireController {

    private final InventaireService service;

    @GetMapping("/logistique/inventaires")
    public List<InventaireService.Inventaire> stocks() {
        return service.listerStocks();
    }

    @GetMapping("/logistique/inventaires/{id}")
    public InventaireService.Inventaire stock(@PathVariable Long id) {
        return service.consulterStock(id);
    }

    @PostMapping("/logistique/inventaires")
    public InventaireService.Inventaire ouvrirStock(@RequestBody InventaireService.Creation req) {
        return service.ouvrirStock(req);
    }

    @PutMapping("/logistique/inventaires/{id}/lignes/{ligneId}")
    public InventaireService.Inventaire compter(@PathVariable Long id, @PathVariable Long ligneId,
                                                @RequestBody InventaireService.Comptage req) {
        return service.compterStock(id, ligneId, req);
    }

    @PostMapping("/logistique/inventaires/{id}/articles/{articleId}")
    public InventaireService.Inventaire ajouterArticle(@PathVariable Long id, @PathVariable Long articleId) {
        return service.ajouterArticle(id, articleId);
    }

    /** Validation par la fonction financière : sous /comptabilite, où le DFIN a l'écriture (séparation des tâches). */
    @PostMapping("/comptabilite/inventaires/stock/{id}/valider")
    public InventaireService.Inventaire validerStock(@PathVariable Long id) {
        return service.validerStock(id);
    }

    @PostMapping("/logistique/inventaires/{id}/annuler")
    public void annulerStock(@PathVariable Long id) {
        service.annulerStock(id);
    }

    @GetMapping("/patrimoine/inventaires")
    public List<InventaireService.Inventaire> immobilisations() {
        return service.listerImmobilisations();
    }

    @GetMapping("/patrimoine/inventaires/{id}")
    public InventaireService.Inventaire immobilisation(@PathVariable Long id) {
        return service.consulterImmobilisations(id);
    }

    @PostMapping("/patrimoine/inventaires")
    public InventaireService.Inventaire ouvrirImmobilisations(@RequestBody InventaireService.Creation req) {
        return service.ouvrirImmobilisations(req);
    }

    @PutMapping("/patrimoine/inventaires/{id}/lignes/{ligneId}")
    public InventaireService.Inventaire constater(@PathVariable Long id, @PathVariable Long ligneId,
                                                  @RequestBody InventaireService.Comptage req) {
        return service.constaterImmobilisation(id, ligneId, req);
    }

    @PostMapping("/comptabilite/inventaires/immobilisations/{id}/valider")
    public InventaireService.Inventaire validerImmobilisations(@PathVariable Long id) {
        return service.validerImmobilisations(id);
    }

    @PostMapping("/patrimoine/inventaires/{id}/annuler")
    public void annulerImmobilisations(@PathVariable Long id) {
        service.annulerImmobilisations(id);
    }
}

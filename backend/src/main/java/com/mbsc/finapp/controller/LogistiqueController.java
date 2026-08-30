package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.logistique.*;
import com.mbsc.finapp.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/logistique")
@RequiredArgsConstructor
public class LogistiqueController {

    private final StockService service;

    // Articles
    @GetMapping("/articles")
    public List<ArticleResponse> listerArticles() {
        return service.listerArticles();
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleResponse creerArticle(@Valid @RequestBody ArticleRequest req) {
        return service.creerArticle(req);
    }

    @PutMapping("/articles/{id}")
    public ArticleResponse modifierArticle(@PathVariable Long id, @Valid @RequestBody ArticleRequest req) {
        return service.modifierArticle(id, req);
    }

    // Entrepôts
    @GetMapping("/entrepots")
    public List<EntrepotResponse> listerEntrepots() {
        return service.listerEntrepots();
    }

    @PostMapping("/entrepots")
    @ResponseStatus(HttpStatus.CREATED)
    public EntrepotResponse creerEntrepot(@Valid @RequestBody EntrepotRequest req) {
        return service.creerEntrepot(req);
    }

    @PutMapping("/entrepots/{id}")
    public EntrepotResponse modifierEntrepot(@PathVariable Long id, @Valid @RequestBody EntrepotRequest req) {
        return service.modifierEntrepot(id, req);
    }

    // Mouvements
    @GetMapping("/mouvements")
    public List<MouvementResponse> listerMouvements() {
        return service.listerMouvements();
    }

    @GetMapping("/mouvements/{id}")
    public MouvementResponse consulterMouvement(@PathVariable Long id) {
        return service.consulterMouvement(id);
    }

    @PostMapping("/mouvements")
    @ResponseStatus(HttpStatus.CREATED)
    public MouvementResponse creerMouvement(@Valid @RequestBody MouvementRequest req) {
        return service.creerMouvement(req);
    }

    @PostMapping("/mouvements/{id}/valider")
    public MouvementResponse valider(@PathVariable Long id) {
        return service.valider(id);
    }

    @PostMapping("/mouvements/{id}/annuler")
    public MouvementResponse annuler(@PathVariable Long id) {
        return service.annuler(id);
    }

    // Stock
    @GetMapping("/stock")
    public List<StockNiveauResponse> etatStock() {
        return service.etatStock();
    }

    @GetMapping("/stock/grand-livre")
    public List<StockGrandLivreResponse> grandLivreStock(
        @RequestParam(required = false) Long article,
        @RequestParam(required = false) Long entrepot,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.grandLivreStock(article, entrepot, du, au);
    }
}

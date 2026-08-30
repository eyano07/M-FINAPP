package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.logistique.ArticleRequest;
import com.mbsc.finapp.dto.logistique.ArticleResponse;
import com.mbsc.finapp.dto.logistique.EntrepotResponse;
import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;
import com.mbsc.finapp.dto.logistique.StockNiveauResponse;
import com.mbsc.finapp.dto.restaurant.EmballageRequest;
import com.mbsc.finapp.dto.restaurant.EmballageResponse;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageRequest;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageResponse;
import com.mbsc.finapp.dto.restaurant.ProvisionEntreeRequest;
import com.mbsc.finapp.dto.restaurant.ProvisionSortieRequest;
import com.mbsc.finapp.dto.restaurant.RestaurantAnalyseIaResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse;
import com.mbsc.finapp.service.RestaurantAnalyseIaService;
import com.mbsc.finapp.service.RestaurantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Module Restaurant.
 *
 * <p>Le prefixe {@code /restaurant} est rattache au module
 * {@code ModuleMetier.RESTAURANT} dans {@code ModuleAccessFilter} : c'est ce
 * qui rend le module activable/desactivable par l'administrateur.</p>
 *
 * <p>Ces endpoints existent precisement pour que le module n'ait jamais a
 * appeler {@code /logistique/*} : ce prefixe-la est rattache au module
 * LOGISTIQUE, que le responsable restaurant n'a pas, et le filtre lui
 * renverrait un 403 sur sa propre carte.</p>
 */
@RestController
@RequestMapping("/restaurant")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService service;
    private final RestaurantAnalyseIaService analyseIaService;

    // ── Tableau de bord ───────────────────────────────────────────────────

    @GetMapping("/tableau-bord")
    public TableauBordRestaurantResponse tableauBord(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        LocalDate fin = au != null ? au : LocalDate.now();
        LocalDate debut = du != null ? du : fin.withDayOfMonth(1);
        return service.tableauBord(debut, fin);
    }

    /**
     * Analyse et recommandations générées par l'IA sur le tableau de bord de
     * la période. Déclenchée à la demande (bouton) plutôt qu'au chargement,
     * comme l'analyse financière comptable — un appel au modèle a un coût et
     * une latence qu'il ne faut pas imposer à chaque consultation.
     */
    @PostMapping("/tableau-bord/analyse-ia")
    public RestaurantAnalyseIaResponse analyserTableauBord(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return analyseIaService.analyser(du, au);
    }

    // ── Carte : plats et boissons ────────────────────────────────────────

    @GetMapping("/carte")
    public List<ArticleResponse> listerCarte() {
        return service.listerCarte();
    }

    @PostMapping("/carte")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleResponse creerArticleCarte(@Valid @RequestBody ArticleRequest req) {
        return service.creerArticleCarte(req);
    }

    @PutMapping("/carte/{id}")
    public ArticleResponse modifierArticleCarte(@PathVariable Long id, @Valid @RequestBody ArticleRequest req) {
        return service.modifierArticleCarte(id, req);
    }

    /** Etat du stock limite aux articles de la carte. */
    @GetMapping("/stock")
    public List<StockNiveauResponse> etatStock() {
        return service.etatStockCarte();
    }

    /**
     * Entrepots disponibles (sans exiger le module LOGISTIQUE) : utilisé par
     * l'écran de demande d'achat de boissons pour choisir la destination de
     * la réception, avant même que la note de frais soit approuvée.
     */
    @GetMapping("/entrepots")
    public List<EntrepotResponse> listerEntrepots() {
        return service.listerEntrepots();
    }

    // ── Emballages consignés ─────────────────────────────────────────────

    @GetMapping("/emballages")
    public List<EmballageResponse> listerEmballages() {
        return service.listerEmballages();
    }

    @PostMapping("/emballages")
    @ResponseStatus(HttpStatus.CREATED)
    public EmballageResponse creerEmballage(@Valid @RequestBody EmballageRequest req) {
        return service.creerEmballage(req);
    }

    @PutMapping("/emballages/{id}")
    public EmballageResponse modifierEmballage(@PathVariable Long id, @Valid @RequestBody EmballageRequest req) {
        return service.modifierEmballage(id, req);
    }

    // ── Circulation des emballages ───────────────────────────────────────

    @GetMapping("/emballages/mouvements")
    public List<MouvementEmballageResponse> listerMouvements(
        @RequestParam(required = false) Long emballageId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.listerMouvements(emballageId, du, au);
    }

    @PostMapping("/emballages/mouvements")
    @ResponseStatus(HttpStatus.CREATED)
    public MouvementEmballageResponse enregistrerMouvement(@Valid @RequestBody MouvementEmballageRequest req) {
        return service.enregistrerMouvement(req);
    }

    // ── Provisions : vivres, épices, charbon... ──────────────────────────

    @GetMapping("/provisions")
    public List<ArticleResponse> listerProvisions() {
        return service.listerProvisions();
    }

    @PostMapping("/provisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleResponse creerProvision(@Valid @RequestBody ArticleRequest req) {
        return service.creerProvision(req);
    }

    @PutMapping("/provisions/{id}")
    public ArticleResponse modifierProvision(@PathVariable Long id, @Valid @RequestBody ArticleRequest req) {
        return service.modifierProvision(id, req);
    }

    @GetMapping("/provisions/stock")
    public List<StockNiveauResponse> etatStockProvisions() {
        return service.etatStockProvisions();
    }

    @GetMapping("/provisions/mouvements")
    public List<StockGrandLivreResponse> grandLivreProvisions(
        @RequestParam(required = false) Long articleId,
        @RequestParam(required = false) Long entrepotId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.grandLivreProvisions(articleId, entrepotId, du, au);
    }

    @PostMapping("/provisions/entrees")
    @ResponseStatus(HttpStatus.CREATED)
    public void recevoirProvision(@Valid @RequestBody ProvisionEntreeRequest req) {
        service.recevoirProvision(req);
    }

    @PostMapping("/provisions/sorties")
    @ResponseStatus(HttpStatus.CREATED)
    public void enregistrerSortieProvision(@Valid @RequestBody ProvisionSortieRequest req) {
        service.enregistrerSortieProvision(req);
    }

    @GetMapping("/provisions/tableau-bord")
    public TableauBordProvisionsResponse tableauBordProvisions(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        LocalDate fin = au != null ? au : LocalDate.now();
        LocalDate debut = du != null ? du : fin.withDayOfMonth(1);
        return service.tableauBordProvisions(debut, fin);
    }

    @PostMapping("/provisions/tableau-bord/analyse-ia")
    public RestaurantAnalyseIaResponse analyserTableauBordProvisions(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return analyseIaService.analyserProvisions(du, au);
    }
}

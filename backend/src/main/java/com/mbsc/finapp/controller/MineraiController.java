package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.logistique.CamionMineraiRequest;
import com.mbsc.finapp.dto.logistique.CamionMineraiResponse;
import com.mbsc.finapp.dto.logistique.ChargeCamionRequest;
import com.mbsc.finapp.dto.logistique.ChargeCamionResponse;
import com.mbsc.finapp.dto.logistique.DupliquerCamionRequest;
import com.mbsc.finapp.dto.logistique.ModeleChargeResponse;
import com.mbsc.finapp.service.MineraiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Camions de minerais : reception par la logistique et consultation.
 *
 * <p>Sous {@code /logistique} : le suivi camion par camion est une extension
 * du stock, il releve donc du meme module (et de la meme garde de module cote
 * filtre — voir {@code ModuleAccessFilter}).</p>
 */
@RestController
@RequestMapping("/logistique/minerais/camions")
@RequiredArgsConstructor
public class MineraiController {

    private final MineraiService service;

    /** Tous les camions, ou ceux d'un minerais donne. */
    @GetMapping
    public List<CamionMineraiResponse> lister(@RequestParam(required = false) Long articleId) {
        return service.lister(articleId);
    }

    /** Camions encore en stock d'un minerais : ce que le caissier peut vendre. */
    @GetMapping("/disponibles")
    public List<CamionMineraiResponse> listerDisponibles(@RequestParam Long articleId) {
        return service.listerDisponibles(articleId);
    }

    /** Camions dont la dette fournisseur reste a solder. */
    @GetMapping("/a-regler")
    public List<CamionMineraiResponse> listerARegler() {
        return service.listerARegler();
    }

    /** Constate l'arrivee d'un chargement — pas encore un achat, voir CamionMinerai. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CamionMineraiResponse receptionner(@Valid @RequestBody CamionMineraiRequest req) {
        return service.receptionner(req);
    }

    /**
     * Duplique un camion (meme minerais, meme entrepot, meme prix, memes
     * frais ad hoc) : seule la plaque change — raccourci pour un arrivage de
     * plusieurs camions identiques.
     */
    @PostMapping("/{id}/dupliquer")
    @ResponseStatus(HttpStatus.CREATED)
    public CamionMineraiResponse dupliquer(@PathVariable Long id, @Valid @RequestBody DupliquerCamionRequest req) {
        return service.dupliquerDepuis(id, req);
    }

    // La validation de l'achat ne vit plus derriere une action directe :
    // elle se fait desormais au paiement d'une note de reglement (voir
    // NoteFraisService.creerReglementCamionsMinerai), qui rattache le
    // camion puis, a son paiement, appelle MineraiService en interne.

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }

    // ── Frais accessoires d'achat ────────────────────────────────────────

    /** Frais accessoires incorpores au cout d'acquisition d'un camion. */
    @GetMapping("/{id}/charges")
    public List<ChargeCamionResponse> listerCharges(@PathVariable Long id) {
        return service.listerCharges(id);
    }

    /** Frais accessoires dont la dette prestataire reste a solder. */
    @GetMapping("/charges/a-regler")
    public List<ChargeCamionResponse> listerChargesARegler() {
        return service.listerChargesARegler();
    }

    /**
     * Ajoute un frais accessoire. Renvoie une liste : un seul element en
     * saisie normale, un par camion en stock si {@code appliquerATousLesCamions}
     * est demande — voir {@code ChargeCamionRequest}.
     */
    @PostMapping("/{id}/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ChargeCamionResponse> ajouterCharge(@PathVariable Long id,
                                                    @Valid @RequestBody ChargeCamionRequest req) {
        return service.ajouterCharge(id, req);
    }

    /** Frais standards d'un minerais, rejoues a chaque reception. */
    @GetMapping("/modeles")
    public List<ModeleChargeResponse> modeles(@RequestParam Long articleId) {
        return service.listerModeles(articleId);
    }

    /** Retire un frais des standards : les lignes deja creees sur les camions ne bougent pas. */
    @DeleteMapping("/modeles/{modeleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerModele(@PathVariable Long modeleId) {
        service.supprimerModele(modeleId);
    }

    @DeleteMapping("/charges/{chargeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerCharge(@PathVariable Long chargeId) {
        service.supprimerCharge(chargeId);
    }
}

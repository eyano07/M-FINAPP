package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.BulletinPaieRequest;
import com.mbsc.finapp.dto.drh.BulletinPaieResponse;
import com.mbsc.finapp.dto.drh.DeclarationSocialeResponse;
import com.mbsc.finapp.dto.drh.ResultatCalculPaie;
import com.mbsc.finapp.service.BulletinPaieService;
import com.mbsc.finapp.service.DeclarationSocialeService;
import com.mbsc.finapp.service.DrhExcelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Bulletins de paie (module DRH_PAIE). Le préfixe /drh/bulletins est
 * rattaché à {@code ModuleMetier.DRH_PAIE} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/bulletins")
@RequiredArgsConstructor
public class BulletinPaieController {

    private final BulletinPaieService service;
    private final DrhExcelService excelService;
    private final DeclarationSocialeService declarationService;

    @GetMapping
    public List<BulletinPaieResponse> lister(@RequestParam Integer mois, @RequestParam Integer annee) {
        return service.listerParPeriode(mois, annee);
    }

    /** Bordereau recapitulatif CNSS / ONEM / INPP / IPR de la periode. */
    @GetMapping("/declaration-sociale")
    public DeclarationSocialeResponse declarationSociale(
        @RequestParam Integer mois, @RequestParam Integer annee) {
        return declarationService.etablir(mois, annee);
    }

    @GetMapping("/{id}")
    public BulletinPaieResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping("/simuler")
    public ResultatCalculPaie simuler(@Valid @RequestBody BulletinPaieRequest req) {
        return service.simuler(req);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BulletinPaieResponse creer(@Valid @RequestBody BulletinPaieRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public BulletinPaieResponse modifier(@PathVariable Long id, @Valid @RequestBody BulletinPaieRequest req) {
        return service.modifier(id, req);
    }

    @PostMapping("/{id}/valider")
    public BulletinPaieResponse valider(@PathVariable Long id) {
        return service.valider(id);
    }

    @PostMapping("/{id}/devalider")
    public BulletinPaieResponse devalider(@PathVariable Long id) {
        return service.devalider(id);
    }

    @PostMapping("/{id}/annuler")
    public BulletinPaieResponse annuler(@PathVariable Long id) {
        return service.annuler(id);
    }

    @PostMapping("/cloturer")
    public List<BulletinPaieResponse> cloturerPeriode(@RequestParam Integer mois, @RequestParam Integer annee) {
        return service.cloturerPeriode(mois, annee);
    }

    /** Registre de paie "DEBOURS MBSC" de la période, en classeur Excel (voir {@code DrhExcelService}). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporter(@RequestParam Integer mois, @RequestParam Integer annee) {
        byte[] classeur = excelService.genererDeboursMbsc(mois, annee);
        String nomFichier = String.format("DEBOURS_MBSC_%02d-%d.xlsx", mois, annee);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
            .body(classeur);
    }
}

package com.mbsc.hotellerie.parametrage;

import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Paramétrage : lecture pour tous, écriture par la direction de l'hébergement et l'administrateur. */
@RestController
@RequiredArgsConstructor
public class ParametrageController {

    private final ParametrageService service;

    @GetMapping("/etablissements")
    public List<ParametrageService.Etablissement> etablissements() {
        SessionCourante.exiger();
        return service.etablissements();
    }

    @PostMapping("/etablissements")
    public ParametrageService.Etablissement creerEtablissement(@Valid @RequestBody ParametrageService.EtablissementRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerEtablissement(null, r);
    }

    @PutMapping("/etablissements/{id}")
    public ParametrageService.Etablissement modifierEtablissement(@PathVariable Long id,
                                                                  @Valid @RequestBody ParametrageService.EtablissementRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerEtablissement(id, r);
    }

    @GetMapping("/types-unite")
    public List<ParametrageService.TypeUnite> types(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.types(etablissementId);
    }

    @PostMapping("/types-unite")
    public ParametrageService.TypeUnite creerType(@Valid @RequestBody ParametrageService.TypeRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerType(null, r);
    }

    @PutMapping("/types-unite/{id}")
    public ParametrageService.TypeUnite modifierType(@PathVariable Long id, @Valid @RequestBody ParametrageService.TypeRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerType(id, r);
    }

    @GetMapping("/unites")
    public List<ParametrageService.Unite> unites(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.unites(etablissementId);
    }

    @PostMapping("/unites")
    public ParametrageService.Unite creerUnite(@Valid @RequestBody ParametrageService.UniteRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerUnite(null, r);
    }

    @PutMapping("/unites/{id}")
    public ParametrageService.Unite modifierUnite(@PathVariable Long id, @Valid @RequestBody ParametrageService.UniteRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerUnite(id, r);
    }

    public record HorsServiceRequest(boolean horsService, String motif) { }

    @PutMapping("/unites/{id}/hors-service")
    public ParametrageService.Unite horsService(@PathVariable Long id, @RequestBody HorsServiceRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE[0], Roles.PARAMETRAGE[1], Roles.TECHNICIEN, Roles.RECEPTION);
        return service.horsService(id, r.horsService(), r.motif());
    }

    @GetMapping("/tarifs")
    public List<ParametrageService.Tarif> tarifs(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.tarifs(etablissementId);
    }

    @PostMapping("/tarifs")
    public ParametrageService.Tarif creerTarif(@Valid @RequestBody ParametrageService.TarifRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerTarif(null, r);
    }

    @PutMapping("/tarifs/{id}")
    public ParametrageService.Tarif modifierTarif(@PathVariable Long id, @Valid @RequestBody ParametrageService.TarifRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerTarif(id, r);
    }

    @GetMapping("/tarifs/propose")
    public Map<String, BigDecimal> prixPropose(@RequestParam Long uniteId, @RequestParam String uniteDuree,
                                               @RequestParam(required = false) LocalDate date) {
        SessionCourante.exiger();
        BigDecimal p = service.prixPropose(uniteId, uniteDuree, date == null ? LocalDate.now() : date);
        return java.util.Collections.singletonMap("prix", p);
    }
}

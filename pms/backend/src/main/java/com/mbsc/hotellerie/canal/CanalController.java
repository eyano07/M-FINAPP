package com.mbsc.hotellerie.canal;

import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Canaux iCal : flux public de chaque unité (adresse secrète) et calendriers importés (direction, administrateur). */
@RestController
@RequiredArgsConstructor
public class CanalController {

    private final CanalService service;

    /** Flux iCal public d'une unité, à donner aux canaux (Booking.com, Airbnb, Expedia/VRBO). */
    @GetMapping("/ical/{jeton}.ics")
    public ResponseEntity<String> flux(@PathVariable String jeton) {
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .body(service.flux(jeton));
    }

    @GetMapping("/canaux")
    public List<CanalService.Canal> canaux() {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.canaux();
    }

    @GetMapping("/canaux/exports")
    public List<CanalService.Export> exports() {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.exports();
    }

    @GetMapping("/canaux/alertes")
    public List<CanalService.Alerte> alertes() {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.alertes();
    }

    @PostMapping("/canaux")
    public CanalService.Canal creer(@Valid @RequestBody CanalService.CanalRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrer(null, r);
    }

    @PutMapping("/canaux/{id}")
    public CanalService.Canal modifier(@PathVariable Long id, @Valid @RequestBody CanalService.CanalRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrer(id, r);
    }

    @DeleteMapping("/canaux/{id}")
    public Map<String, Boolean> supprimer(@PathVariable Long id) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        service.supprimer(id);
        return Map.of("ok", true);
    }

    @PostMapping("/canaux/{id}/synchroniser")
    public CanalService.Bilan synchroniser(@PathVariable Long id) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.synchroniser(id);
    }

    @PostMapping("/canaux/alertes/{id}/traitee")
    public Map<String, Boolean> traitee(@PathVariable Long id) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        service.alerteTraitee(id);
        return Map.of("ok", true);
    }

    @PostMapping("/unites/{id}/ical/renouveler")
    public Map<String, String> renouveler(@PathVariable Long id) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return Map.of("jeton", service.renouvelerJeton(id));
    }
}

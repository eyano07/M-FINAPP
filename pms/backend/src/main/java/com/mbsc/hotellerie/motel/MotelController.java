package com.mbsc.hotellerie.motel;

import com.mbsc.hotellerie.parametrage.ParametrageService;
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

import java.time.OffsetDateTime;
import java.util.List;

/** Motel : arrivée express, forfaits par créneau, parking et véhicules. */
@RestController
@RequiredArgsConstructor
public class MotelController {

    private final MotelService service;
    private final ParametrageService parametrage;

    @PostMapping("/motel/express")
    public MotelService.Express express(@Valid @RequestBody MotelService.ExpressRequest e) {
        return service.express(e, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    /** Forfaits proposés pour une unité et une heure d'arrivée (maintenant par défaut). */
    @GetMapping("/unites/{id}/forfaits")
    public List<ParametrageService.Tarif> forfaits(@PathVariable Long id, @RequestParam(required = false) OffsetDateTime arrivee) {
        SessionCourante.exiger();
        return parametrage.forfaits(id, arrivee == null ? OffsetDateTime.now() : arrivee);
    }

    @GetMapping("/parking/places")
    public List<MotelService.Place> places(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.places(etablissementId);
    }

    @PostMapping("/parking/places")
    public MotelService.Place creerPlace(@Valid @RequestBody MotelService.PlaceRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerPlace(null, r);
    }

    @PutMapping("/parking/places/{id}")
    public MotelService.Place modifierPlace(@PathVariable Long id, @Valid @RequestBody MotelService.PlaceRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return service.enregistrerPlace(id, r);
    }

    @GetMapping("/parking/vehicules")
    public List<MotelService.Vehicule> vehicules(@RequestParam(required = false) Long etablissementId,
                                                 @RequestParam(required = false) Long reservationId) {
        SessionCourante.exiger();
        return service.vehicules(etablissementId, reservationId);
    }

    @PostMapping("/reservations/{id}/vehicules")
    public MotelService.Vehicule ajouterVehicule(@PathVariable Long id, @Valid @RequestBody MotelService.VehiculeRequest v) {
        return service.ajouterVehicule(id, v, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    @PostMapping("/parking/vehicules/{id}/sortie")
    public void sortir(@PathVariable Long id) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        service.sortirVehicule(id);
    }
}

package com.mbsc.hotellerie.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** Réservations, arrivées, folios et départs. L'administrateur de M-FINAPP consulte et réserve mais n'encaisse pas. */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService service;
    private final ReferentielService referentiel;

    @GetMapping("/reservations")
    public List<ReservationService.Reservation> lister(@RequestParam(required = false) OffsetDateTime du,
                                                       @RequestParam(required = false) OffsetDateTime au,
                                                       @RequestParam(required = false) Long etablissementId,
                                                       @RequestParam(required = false) String statut,
                                                       @RequestParam(required = false) String q) {
        SessionCourante.exiger();
        return service.lister(du, au, etablissementId, statut, q);
    }

    @GetMapping("/reservations/{id}")
    public ReservationService.Folio detail(@PathVariable Long id) {
        return service.folio(id, SessionCourante.exiger());
    }

    @PostMapping("/reservations")
    public ReservationService.Reservation creer(@Valid @RequestBody ReservationService.ReservationRequest r) {
        return service.creer(r, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    @PutMapping("/reservations/{id}")
    public ReservationService.Reservation modifier(@PathVariable Long id, @Valid @RequestBody ReservationService.ModificationRequest r) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.modifier(id, r);
    }

    public record StatutRequest(String statut, String motif) { }

    @PostMapping("/reservations/{id}/statut")
    public ReservationService.Reservation statut(@PathVariable Long id, @RequestBody StatutRequest r) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.changerStatut(id, r.statut(), r.motif());
    }

    @PostMapping("/reservations/{id}/arrivee")
    public ReservationService.Reservation arrivee(@PathVariable Long id, @Valid @RequestBody ReservationService.ArriveeRequest r) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.arriver(id, r);
    }

    @PostMapping("/reservations/{id}/extras")
    public ReservationService.Folio extra(@PathVariable Long id, @Valid @RequestBody ReservationService.ExtraRequest r) {
        return service.ajouterExtra(id, r, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    @DeleteMapping("/reservations/{id}/extras/{ligneId}")
    public ReservationService.Folio retirerExtra(@PathVariable Long id, @PathVariable Long ligneId) {
        return service.retirerExtra(id, ligneId, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    @PostMapping("/reservations/{id}/fonds")
    public ReservationService.Folio fonds(@PathVariable Long id, @Valid @RequestBody ReservationService.FondsRequest r) {
        return service.fonds(id, r, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    @PostMapping("/reservations/{id}/depart")
    public ReservationService.Depart depart(@PathVariable Long id, @Valid @RequestBody ReservationService.DepartRequest r) {
        return service.partir(id, r, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    @PostMapping("/reservations/{id}/encaisser")
    public ReservationService.Depart encaisser(@PathVariable Long id) {
        return service.encaisser(id, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    /** Référentiel de M-FINAPP pour les écrans d'encaissement (banques, mobile money, entrepôts, articles, TVA). */
    @GetMapping("/referentiel")
    public JsonNode referentiel() {
        return referentiel.referentiel(SessionCourante.exiger());
    }
}

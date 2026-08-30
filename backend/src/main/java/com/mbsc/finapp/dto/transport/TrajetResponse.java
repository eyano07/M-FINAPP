package com.mbsc.finapp.dto.transport;

import com.mbsc.finapp.domain.Trajet;
import com.mbsc.finapp.domain.enums.StatutTrajet;

import java.math.BigDecimal;
import java.time.Instant;

public record TrajetResponse(
    Long id,
    String reference,
    Long vehiculeId,
    String vehiculeImmatriculation,
    Long conducteurId,
    String conducteurEmail,
    Instant dateDepart,
    Instant dateArrivee,
    String origine,
    String destination,
    BigDecimal distanceKm,
    StatutTrajet statut
) {
    public static TrajetResponse from(Trajet t) {
        var v = t.getVehicule();
        var c = t.getConducteur();
        return new TrajetResponse(
            t.getId(),
            t.getReference(),
            v == null ? null : v.getId(),
            v == null ? null : v.getImmatriculation(),
            c == null ? null : c.getId(),
            c == null ? null : c.getEmail(),
            t.getDateDepart(),
            t.getDateArrivee(),
            t.getOrigine(),
            t.getDestination(),
            t.getDistanceKm(),
            t.getStatut()
        );
    }
}

package com.mbsc.finapp.dto.transport;

import com.mbsc.finapp.domain.Vehicule;

import java.time.LocalDate;

public record VehiculeResponse(
    Long id,
    String immatriculation,
    String marque,
    String modele,
    String type,
    LocalDate dateAcquisition,
    boolean actif
) {
    public static VehiculeResponse from(Vehicule v) {
        return new VehiculeResponse(
            v.getId(),
            v.getImmatriculation(),
            v.getMarque(),
            v.getModele(),
            v.getType(),
            v.getDateAcquisition(),
            v.isActif()
        );
    }
}

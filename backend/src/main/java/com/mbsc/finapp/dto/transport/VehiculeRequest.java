package com.mbsc.finapp.dto.transport;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record VehiculeRequest(
    @NotBlank String immatriculation,
    String marque,
    String modele,
    String type,
    LocalDate dateAcquisition,
    Boolean actif
) {}

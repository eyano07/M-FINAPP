package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;

public record EntrepotRequest(
    @NotBlank String code,
    @NotBlank String nom,
    String localisation,
    Boolean actif
) {}

package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.NotBlank;

public record SalleRequest(
    @NotBlank String nom,
    Integer ordre,
    Boolean actif
) {}

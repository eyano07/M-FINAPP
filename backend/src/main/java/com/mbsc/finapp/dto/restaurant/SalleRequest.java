package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record SalleRequest(
    @NotBlank String nom,
    Integer ordre,
    Boolean actif,
    @PositiveOrZero(message = "La majoration ne peut pas être négative")
    @DecimalMax(value = "100", message = "La majoration ne peut pas dépasser 100 %")
    BigDecimal majorationPourcentage
) {}

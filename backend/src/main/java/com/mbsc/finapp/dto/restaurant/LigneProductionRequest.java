package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * @param quantite quantite TOTALE consommee par la production (pas par portion).
 */
public record LigneProductionRequest(
    @NotNull Long provisionId,
    @NotNull @Positive BigDecimal quantite
) {}

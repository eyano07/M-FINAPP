package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * @param quantite quantite de la provision pour UNE portion du plat.
 */
public record LigneRecetteRequest(
    @NotNull Long provisionId,
    @NotNull @Positive BigDecimal quantite
) {}

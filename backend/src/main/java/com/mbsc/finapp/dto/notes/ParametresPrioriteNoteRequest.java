package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Seuils de reserve de tresorerie par priorite, saisis par le DA.
 * Montants en devise de base (USD) ; 0 = aucune restriction pour cette
 * priorite — voir {@code ParametresPrioriteNote}.
 */
public record ParametresPrioriteNoteRequest(
    @NotNull @DecimalMin("0") BigDecimal seuilBasse,
    @NotNull @DecimalMin("0") BigDecimal seuilMoyenne,
    @NotNull @DecimalMin("0") BigDecimal seuilHaute
) {}

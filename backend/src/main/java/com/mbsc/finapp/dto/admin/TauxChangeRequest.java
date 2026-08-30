package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Corps de la requête POST /admin/taux-change.
 */
public record TauxChangeRequest(
    @NotNull @Positive BigDecimal taux,
    @NotNull LocalDate dateEffet,
    String note,
    /**
     * Reference verifiable de la source (ex. "BCC, cote du 18/08/2026").
     * Obligatoire : un taux sans source retracable n'est pas exploitable
     * en cas de controle (audit du 18/08/2026, constat A-03).
     */
    @jakarta.validation.constraints.NotBlank String source
) {}

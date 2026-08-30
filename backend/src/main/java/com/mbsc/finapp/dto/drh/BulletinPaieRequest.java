package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BulletinPaieRequest(
    @NotNull Long employeId,
    @NotNull @Min(1) @Max(12) Integer mois,
    @NotNull @Min(2000) @Max(2100) Integer annee,
    @NotNull @PositiveOrZero BigDecimal salaireBaseUsd,
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal presencePct,
    @PositiveOrZero BigDecimal conge,
    @PositiveOrZero BigDecimal heuresSupplementaires,
    @PositiveOrZero BigDecimal allocationFamiliale,
    @PositiveOrZero BigDecimal primeDiplome,
    @PositiveOrZero BigDecimal primeAnciennete,
    @PositiveOrZero BigDecimal primeRendement,
    @PositiveOrZero BigDecimal avanceSalaire,
    @PositiveOrZero BigDecimal pret,
    LocalDate datePaiement
) {}

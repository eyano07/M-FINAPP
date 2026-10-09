package com.mbsc.finapp.dto.rapprochement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Opération d'un relevé : {@code entree} (argent reçu) ou {@code sortie} (argent sorti), dans la devise du compte. */
public record LigneReleveRequest(
    @NotNull LocalDate dateOperation,
    @NotBlank @Size(max = 500) String libelle,
    @Size(max = 100) String reference,
    @NotNull @PositiveOrZero BigDecimal entree,
    @NotNull @PositiveOrZero BigDecimal sortie
) {}

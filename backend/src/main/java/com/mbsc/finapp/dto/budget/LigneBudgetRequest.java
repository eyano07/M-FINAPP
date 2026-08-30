package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Une ligne previsionnelle rattachee a un compte OHADA.
 */
public record LigneBudgetRequest(

    @NotBlank
    String compteNumero,

    @NotNull
    @DecimalMin(value = "0.00", message = "Le montant prevu ne peut etre negatif")
    BigDecimal montantPrevu
) {}

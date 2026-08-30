package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Enregistrement d'un nouveau taux de TVA (ADMIN). */
public record TauxTvaRequest(

    @NotNull(message = "Le taux est obligatoire")
    @DecimalMin(value = "0.0", message = "Le taux ne peut pas etre negatif")
    @DecimalMax(value = "100.0", message = "Le taux ne peut pas depasser 100 %")
    BigDecimal taux,

    @NotNull(message = "La date d'effet est obligatoire")
    LocalDate dateEffet,

    @Size(max = 500)
    String note
) {}

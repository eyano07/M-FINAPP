package com.mbsc.finapp.dto.budget;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creation / mise a jour d'un budget previsionnel (etat BROUILLON).
 */
public record BudgetRequest(
    @NotBlank
    @Size(max = 200)
    String intitule,

    @NotNull
    @Min(value = 2000, message = "Exercice invalide")
    @Max(value = 2100, message = "Exercice invalide")
    Integer exercice,

    @Size(max = 1000)
    String observation,

    @NotEmpty(message = "Le budget doit comporter au moins une ligne")
    @Valid
    List<LigneBudgetRequest> lignes
) {}

package com.mbsc.finapp.dto.budget;

import jakarta.validation.Valid;
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
    Integer exercice,

    @Size(max = 1000)
    String observation,

    @NotEmpty
    @Valid
    List<LigneBudgetRequest> lignes
) {}

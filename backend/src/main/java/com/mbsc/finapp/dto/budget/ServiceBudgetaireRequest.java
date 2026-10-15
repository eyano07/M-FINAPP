package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creation / modification d'un service : son nom et le nom de son responsable. */
public record ServiceBudgetaireRequest(
    @NotBlank(message = "Le nom du service est obligatoire")
    @Size(max = 120)
    String nom,

    @NotBlank(message = "Le nom du responsable du service est obligatoire")
    @Size(max = 150)
    String responsable,

    @Size(max = 500)
    String description
) {}

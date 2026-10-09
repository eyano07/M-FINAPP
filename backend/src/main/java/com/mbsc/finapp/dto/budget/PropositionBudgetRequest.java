package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Demande de proposition de budget annuel a partir des donnees reelles du systeme. */
public record PropositionBudgetRequest(
    @NotNull @Min(2000) @Max(2100) Integer exercice,
    /** Hypotheses de la direction a prendre en compte (ex. « +10 % de ventes, embauche d'un comptable »). */
    @Size(max = 1000) String hypotheses
) {}

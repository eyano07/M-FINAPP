package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.Size;

/** Justification d'une depense non couverte par le budget, precisee par le createur avant la soumission. */
public record JustificationBudgetRequest(@Size(max = 1000) String justification) {}

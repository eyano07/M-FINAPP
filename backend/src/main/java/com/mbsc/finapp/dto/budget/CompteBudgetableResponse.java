package com.mbsc.finapp.dto.budget;

/** Compte du plan comptable pouvant porter une ligne budgetaire (classes 2, 6, 7, 8). */
public record CompteBudgetableResponse(
    String numero,
    String libelle,
    Integer classe,
    String type,
    String section,
    boolean imputable
) {}

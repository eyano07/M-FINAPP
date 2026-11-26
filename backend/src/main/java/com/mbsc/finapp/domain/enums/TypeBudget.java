package com.mbsc.finapp.domain.enums;

/**
 * Budget principal de l'entreprise, ou budget secondaire (parallèle) : le budget d'un projet, par exemple (V162).
 * Les notes de frais relèvent du budget principal, sauf celles rattachées à un budget secondaire.
 */
public enum TypeBudget {
    PRINCIPAL,
    SECONDAIRE
}

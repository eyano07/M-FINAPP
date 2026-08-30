package com.mbsc.finapp.domain.enums;

/**
 * Cycle de vie d'un budget previsionnel.
 */
public enum StatutBudget {
    BROUILLON,
    SOUMIS,        // DFIN soumet
    APPROUVE,      // DA approuve (apres CA)
    REJETE,
    EN_EXECUTION
}

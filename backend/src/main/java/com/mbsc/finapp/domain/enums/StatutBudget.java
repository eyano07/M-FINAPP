package com.mbsc.finapp.domain.enums;

/**
 * Cycle de vie d'un budget previsionnel.
 *
 * <pre>
 *   BROUILLON --soumettre (DFIN)--> SOUMIS --approuver (DA)--> APPROUVE --demarrer (DFIN)--> EN_EXECUTION --cloturer--> CLOTURE
 *                                    SOUMIS --rejeter (DA, motif)--> REJETE --reprendre (DFIN)--> BROUILLON
 *   EN_EXECUTION --reviser (DFIN)--> nouvelle version BROUILLON ; quand elle est mise en execution, l'ancienne passe REMPLACE.
 * </pre>
 */
public enum StatutBudget {
    BROUILLON,
    SOUMIS,        // DFIN soumet
    APPROUVE,      // DA approuve (apres CA)
    REJETE,
    EN_EXECUTION,
    /** Remplace par une revision mise en execution : conserve pour la tracabilite, plus utilise pour le controle. */
    REMPLACE,
    /** Exercice termine : le budget reste consultable, il ne controle plus les depenses. */
    CLOTURE
}

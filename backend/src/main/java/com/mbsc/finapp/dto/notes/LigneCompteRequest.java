package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.NotNull;

/**
 * Reaffectation du compte d'imputation d'une ligne existante, identifiee
 * par son identifiant.
 */
public record LigneCompteRequest(

    @NotNull
    Long ligneId,

    /** Numero du compte OHADA d'imputation (optionnel : remet le fallback 6588 au paiement si absent). */
    String compteImputation
) {}

package com.mbsc.finapp.dto.notes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Reaffectation du compte d'imputation d'une ou plusieurs lignes d'une note
 * de frais soumise, par le DFIN lors de sa verification.
 */
public record ModifierComptesRequest(

    @NotEmpty(message = "Au moins une ligne doit etre indiquee")
    @Valid
    List<LigneCompteRequest> lignes
) {}

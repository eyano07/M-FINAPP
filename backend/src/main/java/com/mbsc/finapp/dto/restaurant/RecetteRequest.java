package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Fiche technique complete d'un plat, envoyee en une fois : les lignes
 * absentes de la liste sont supprimees (meme convention que
 * {@link PlanSalleRequest}). Une liste vide efface la fiche.
 */
public record RecetteRequest(
    @NotNull @Valid List<LigneRecetteRequest> lignes
) {}

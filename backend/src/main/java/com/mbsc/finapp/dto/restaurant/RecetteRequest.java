package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Fiche technique complete d'un plat, envoyee en une fois : les lignes
 * absentes de la liste sont supprimees (meme convention que
 * {@link PlanSalleRequest}). Une liste vide efface la fiche.
 *
 * @param dureeConservationJours duree de conservation du plat, en jours : les portions d'une production expirent a sa
 *                               date de production + cette duree (0 : le jour meme), et celles qui n'ont pas ete
 *                               vendues ensuite sont perimees. Absente : le plat ne se perime pas. Elle fait partie
 *                               de la fiche, envoyee en meme temps que ses lignes.
 */
public record RecetteRequest(
    @NotNull @Valid List<LigneRecetteRequest> lignes,
    @Min(value = 0, message = "La duree de conservation ne peut pas etre negative")
    @Max(value = 365, message = "La duree de conservation ne peut pas depasser 365 jours")
    Integer dureeConservationJours
) {}

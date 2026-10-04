package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ParametresRestaurantRequest(
    @NotNull Devise deviseAffichage,
    /** Chiffres après la virgule pour les montants affichés ; null = automatique (FC sans décimale, USD à 2). */
    @Min(0) @Max(4) Integer decimalesMontants,
    /** Chiffres après la virgule, au plus, pour les quantités affichées ; null = inchangé. */
    @Min(0) @Max(3) Integer decimalesQuantites
) {}

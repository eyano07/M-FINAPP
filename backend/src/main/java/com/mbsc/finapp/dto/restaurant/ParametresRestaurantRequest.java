package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.validation.constraints.NotNull;

public record ParametresRestaurantRequest(
    @NotNull Devise deviseAffichage
) {}

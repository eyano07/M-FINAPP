package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.enums.Devise;

public record ParametresRestaurantResponse(
    Devise deviseAffichage
) {
    public static ParametresRestaurantResponse from(ParametresRestaurant p) {
        return new ParametresRestaurantResponse(p.getDeviseAffichage());
    }
}

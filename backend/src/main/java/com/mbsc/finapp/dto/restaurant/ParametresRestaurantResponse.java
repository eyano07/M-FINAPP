package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.enums.Devise;

public record ParametresRestaurantResponse(
    Devise deviseAffichage,
    /** null = automatique (FC sans décimale, USD à 2). */
    Integer decimalesMontants,
    Integer decimalesQuantites
) {
    public static ParametresRestaurantResponse from(ParametresRestaurant p) {
        return new ParametresRestaurantResponse(p.getDeviseAffichage(),
            p.getDecimalesMontants(), p.getDecimalesQuantites());
    }
}

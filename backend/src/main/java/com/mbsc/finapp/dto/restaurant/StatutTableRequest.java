package com.mbsc.finapp.dto.restaurant;

/** Bascule d'occupation d'une table, independante du plan (voir RestaurantService.changerStatutTable). */
public record StatutTableRequest(
    boolean occupee
) {}

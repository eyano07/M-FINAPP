package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.SalleRestaurant;
import com.mbsc.finapp.domain.TableRestaurant;

import java.util.List;

public record SalleResponse(
    Long id,
    String nom,
    int ordre,
    boolean actif,
    List<TableResponse> tables
) {
    public static SalleResponse from(SalleRestaurant s, List<TableRestaurant> tables) {
        return new SalleResponse(
            s.getId(), s.getNom(), s.getOrdre(), s.isActif(),
            tables.stream().map(TableResponse::from).toList()
        );
    }

    /** Variante utilisee quand les badges de commande ont deja ete calcules (voir RestaurantService.listerSalles). */
    public static SalleResponse avecTables(SalleRestaurant s, List<TableResponse> tablesDto) {
        return new SalleResponse(s.getId(), s.getNom(), s.getOrdre(), s.isActif(), tablesDto);
    }
}

package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.SalleRestaurant;
import com.mbsc.finapp.domain.TableRestaurant;

import java.math.BigDecimal;
import java.util.List;

public record SalleResponse(
    Long id,
    String nom,
    int ordre,
    boolean actif,
    BigDecimal majorationPourcentage,
    List<TableResponse> tables
) {
    public static SalleResponse from(SalleRestaurant s, List<TableRestaurant> tables) {
        return new SalleResponse(
            s.getId(), s.getNom(), s.getOrdre(), s.isActif(), s.getMajorationPourcentage(),
            tables.stream().map(TableResponse::from).toList()
        );
    }

    /** Variante utilisee quand les badges de commande ont deja ete calcules (voir RestaurantService.listerSalles). */
    public static SalleResponse avecTables(SalleRestaurant s, List<TableResponse> tablesDto) {
        return new SalleResponse(s.getId(), s.getNom(), s.getOrdre(), s.isActif(), s.getMajorationPourcentage(), tablesDto);
    }
}

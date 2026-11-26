package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.LigneProduction;

import java.math.BigDecimal;

public record LigneProductionResponse(
    Long id,
    Long provisionId,
    String provisionCode,
    String provisionLibelle,
    String uniteMesure,
    BigDecimal quantite,
    BigDecimal coutUnitaire,
    BigDecimal montant
) {
    public static LigneProductionResponse from(LigneProduction l) {
        var p = l.getProvision();
        return new LigneProductionResponse(
            l.getId(),
            p.getId(), p.getCode(), p.getLibelle(), p.getUniteMesure(),
            l.getQuantite(), l.getCoutUnitaire(), l.getMontant()
        );
    }
}

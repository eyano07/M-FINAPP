package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.LigneBudget;

import java.math.BigDecimal;

/**
 * Une ligne de budget en lecture.
 */
public record LigneBudgetResponse(
    Long id,
    String compteNumero,
    String compteLibelle,
    BigDecimal montantPrevu,
    BigDecimal montantRealise
) {
    public static LigneBudgetResponse from(LigneBudget l) {
        var compte = l.getCompte();
        return new LigneBudgetResponse(
            l.getId(),
            compte == null ? null : compte.getNumero(),
            compte == null ? null : compte.getLibelle(),
            l.getMontantPrevu(),
            l.getMontantRealise()
        );
    }
}

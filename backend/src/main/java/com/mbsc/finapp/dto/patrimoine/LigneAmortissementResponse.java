package com.mbsc.finapp.dto.patrimoine;

import com.mbsc.finapp.domain.LigneAmortissement;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LigneAmortissementResponse(
    Long id,
    LocalDate periode,
    BigDecimal dotation,
    BigDecimal cumul,
    BigDecimal valeurNette,
    boolean comptabilise,
    String pieceReference
) {
    public static LigneAmortissementResponse from(LigneAmortissement l) {
        return new LigneAmortissementResponse(
            l.getId(), l.getPeriode(), l.getDotation(), l.getCumul(), l.getValeurNette(),
            l.isComptabilise(), l.getPiece() != null ? l.getPiece().getReference() : null);
    }
}

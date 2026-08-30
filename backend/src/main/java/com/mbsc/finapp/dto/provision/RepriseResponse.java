package com.mbsc.finapp.dto.provision;

import com.mbsc.finapp.domain.ProvisionReprise;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RepriseResponse(
    Long id,
    BigDecimal montant,
    String motif,
    LocalDate dateReprise,
    String compteRepriseNumero,
    String pieceReference
) {
    public static RepriseResponse from(ProvisionReprise r) {
        return new RepriseResponse(
            r.getId(),
            r.getMontant(),
            r.getMotif(),
            r.getDateReprise(),
            r.getCompteReprise().getNumero(),
            r.getPiece() == null ? null : r.getPiece().getReference()
        );
    }
}

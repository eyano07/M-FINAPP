package com.mbsc.finapp.dto.transport;

import com.mbsc.finapp.domain.DepenseVehicule;
import com.mbsc.finapp.domain.enums.TypeDepenseVehicule;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepenseResponse(
    Long id,
    String reference,
    Long vehiculeId,
    String vehiculeImmatriculation,
    Long trajetId,
    String trajetReference,
    TypeDepenseVehicule type,
    BigDecimal montant,
    LocalDate dateDepense,
    String compteChargeNumero,
    String pieceReference
) {
    public static DepenseResponse from(DepenseVehicule d) {
        var v = d.getVehicule();
        var t = d.getTrajet();
        return new DepenseResponse(
            d.getId(),
            d.getReference(),
            v == null ? null : v.getId(),
            v == null ? null : v.getImmatriculation(),
            t == null ? null : t.getId(),
            t == null ? null : t.getReference(),
            d.getType(),
            d.getMontant(),
            d.getDateDepense(),
            d.getCompteCharge() == null ? null : d.getCompteCharge().getNumero(),
            d.getPiece() == null ? null : d.getPiece().getReference()
        );
    }
}

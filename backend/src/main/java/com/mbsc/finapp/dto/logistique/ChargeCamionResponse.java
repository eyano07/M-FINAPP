package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.ChargeCamionMinerai;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ChargeCamionResponse(
    Long id,
    Long camionId,
    String camionPlaque,
    String libelle,
    String compteChargeNumero,
    String compteChargeLibelle,
    BigDecimal montant,
    LocalDate dateCharge,
    boolean regle,
    String pieceReference,
    String transactionReglementReference
) {
    public static ChargeCamionResponse from(ChargeCamionMinerai c) {
        return new ChargeCamionResponse(
            c.getId(),
            c.getCamion().getId(),
            c.getCamion().getPlaque(),
            c.getLibelle(),
            c.getCompteCharge().getNumero(),
            c.getCompteCharge().getLibelle(),
            c.getMontant(),
            c.getDateCharge(),
            c.isRegle(),
            c.getPiece() == null ? null : c.getPiece().getReference(),
            c.getTransactionReglement() == null ? null : c.getTransactionReglement().getReference()
        );
    }
}

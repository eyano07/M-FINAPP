package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.ModeleChargeMinerai;

import java.math.BigDecimal;

/** Frais accessoire standard d'un minerais — voir {@code ModeleChargeMinerai}. */
public record ModeleChargeResponse(
    Long id,
    Long articleId,
    String articleLibelle,
    String libelle,
    String compteChargeNumero,
    String compteChargeLibelle,
    BigDecimal montant
) {
    public static ModeleChargeResponse from(ModeleChargeMinerai m) {
        return new ModeleChargeResponse(
            m.getId(),
            m.getArticle().getId(),
            m.getArticle().getLibelle(),
            m.getLibelle(),
            m.getCompteCharge().getNumero(),
            m.getCompteCharge().getLibelle(),
            m.getMontant()
        );
    }
}

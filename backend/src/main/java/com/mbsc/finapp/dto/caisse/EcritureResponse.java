package com.mbsc.finapp.dto.caisse;

import com.mbsc.finapp.domain.EcritureGrandLivre;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une ligne du Grand Livre.
 */
public record EcritureResponse(
    Long id,
    String compteNumero,
    String compteLibelle,
    BigDecimal debit,
    BigDecimal credit,
    String libelle,
    LocalDate dateEcriture,
    String transactionReference,
    BigDecimal tauxApplique
) {
    public static EcritureResponse from(EcritureGrandLivre e) {
        var compte = e.getCompte();
        var trx = e.getTransaction();
        return new EcritureResponse(
            e.getId(),
            compte == null ? null : compte.getNumero(),
            compte == null ? null : compte.getLibelle(),
            e.getDebit(),
            e.getCredit(),
            e.getLibelle(),
            e.getDateEcriture(),
            trx == null ? null : trx.getReference(),
            e.getTauxApplique()
        );
    }
}

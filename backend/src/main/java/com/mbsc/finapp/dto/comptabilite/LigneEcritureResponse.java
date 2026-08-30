package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.EcritureGrandLivre;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param tauxApplique taux de change fige au moment de l'operation, qui permet
 *                     de reafficher la ligne en USD sans dependre du taux courant
 */
public record LigneEcritureResponse(
    Long id,
    String compteNumero,
    String compteLibelle,
    BigDecimal debit,
    BigDecimal credit,
    String libelle,
    LocalDate dateEcriture,
    BigDecimal tauxApplique
) {
    public static LigneEcritureResponse from(EcritureGrandLivre e) {
        var compte = e.getCompte();
        return new LigneEcritureResponse(
            e.getId(),
            compte == null ? null : compte.getNumero(),
            compte == null ? null : compte.getLibelle(),
            e.getDebit(),
            e.getCredit(),
            e.getLibelle(),
            e.getDateEcriture(),
            e.getTauxApplique()
        );
    }
}

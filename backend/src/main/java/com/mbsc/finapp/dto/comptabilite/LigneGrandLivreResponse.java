package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.EcritureGrandLivre;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param tauxApplique taux de change fige au moment de l'operation, qui permet
 *                     de reafficher la ligne en USD sans dependre du taux courant
 */
public record LigneGrandLivreResponse(
    Long id,
    LocalDate dateEcriture,
    String pieceReference,
    String transactionReference,
    String libelle,
    BigDecimal debit,
    BigDecimal credit,
    BigDecimal soldeProgressif,
    BigDecimal tauxApplique
) {
    public static LigneGrandLivreResponse of(EcritureGrandLivre e, BigDecimal soldeProgressif) {
        var piece = e.getPiece();
        var trx = e.getTransaction();
        return new LigneGrandLivreResponse(
            e.getId(),
            e.getDateEcriture(),
            piece == null ? null : piece.getReference(),
            trx == null ? null : trx.getReference(),
            e.getLibelle(),
            e.getDebit(),
            e.getCredit(),
            soldeProgressif,
            e.getTauxApplique()
        );
    }
}

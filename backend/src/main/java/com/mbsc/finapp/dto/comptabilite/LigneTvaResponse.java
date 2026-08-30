package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.EcritureGrandLivre;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une écriture individuelle de TVA (collectée ou récupérable), pour la piste d'audit. */
public record LigneTvaResponse(
    LocalDate date,
    String pieceReference,
    String journal,
    String compteNumero,
    String compteLibelle,
    String libelle,
    BigDecimal montant,
    String nature   // "COLLECTEE" ou "RECUPERABLE"
) {
    public static LigneTvaResponse collectee(EcritureGrandLivre e) {
        return of(e, e.getCredit().subtract(e.getDebit()), "COLLECTEE");
    }

    public static LigneTvaResponse recuperable(EcritureGrandLivre e) {
        return of(e, e.getDebit().subtract(e.getCredit()), "RECUPERABLE");
    }

    private static LigneTvaResponse of(EcritureGrandLivre e, BigDecimal montant, String nature) {
        var piece = e.getPiece();
        return new LigneTvaResponse(
            e.getDateEcriture(),
            piece == null ? null : piece.getReference(),
            piece == null ? null : piece.getJournal().name(),
            e.getCompte().getNumero(),
            e.getCompte().getLibelle(),
            e.getLibelle(),
            montant,
            nature
        );
    }
}

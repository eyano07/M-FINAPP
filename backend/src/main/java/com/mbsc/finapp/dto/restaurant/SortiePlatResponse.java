package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.SortiePlat;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.MotifSortiePlat;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SortiePlatResponse(
    Long id,
    Long articleId,
    String articleCode,
    String articleLibelle,
    String uniteMesure,
    String entrepotCode,
    BigDecimal quantite,
    MotifSortiePlat motif,
    String motifLibelle,
    String precision,
    LocalDate dateSortie,
    /** Coût sorti, en USD comme le grand livre. */
    BigDecimal valeur,
    String mouvementReference,
    /** Pièce du journal STOCK ; null si le plat avait un coût nul (aucune écriture). */
    String pieceReference,
    boolean annulee,
    String creePar
) {
    public static SortiePlatResponse from(SortiePlat s) {
        var m = s.getMouvementStock();
        return new SortiePlatResponse(
            s.getId(),
            s.getArticle().getId(),
            s.getArticle().getCode(),
            s.getArticle().getLibelle(),
            s.getArticle().getUniteMesure(),
            s.getEntrepot().getCode(),
            s.getQuantite(),
            s.getMotif(),
            s.getMotif().libelle(),
            s.getPrecisionMotif(),
            s.getDateSortie(),
            s.getValeur(),
            m.getReference(),
            m.getPiece() != null ? m.getPiece().getReference() : null,
            s.isAnnulee(),
            nom(s.getCreatedBy())
        );
    }

    private static String nom(User u) {
        if (u == null) {
            return null;
        }
        String complet = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return complet.isEmpty() ? u.getEmail() : complet;
    }
}

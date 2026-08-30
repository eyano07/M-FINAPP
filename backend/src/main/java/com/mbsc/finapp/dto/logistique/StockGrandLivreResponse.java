package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.StockGrandLivre;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StockGrandLivreResponse(
    Long id,
    LocalDate dateEcriture,
    String articleCode,
    String entrepotCode,
    String mouvementReference,
    BigDecimal qteEntree,
    BigDecimal qteSortie,
    BigDecimal qteApres,
    BigDecimal valeurUnitaire,
    BigDecimal valeurApres
) {
    public static StockGrandLivreResponse from(StockGrandLivre s) {
        return new StockGrandLivreResponse(
            s.getId(),
            s.getDateEcriture(),
            s.getArticle() == null ? null : s.getArticle().getCode(),
            s.getEntrepot() == null ? null : s.getEntrepot().getCode(),
            s.getMouvement() == null ? null : s.getMouvement().getReference(),
            s.getQteEntree(),
            s.getQteSortie(),
            s.getQteApres(),
            s.getValeurUnitaire(),
            s.getValeurApres()
        );
    }
}

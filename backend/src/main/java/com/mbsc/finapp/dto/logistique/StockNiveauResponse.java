package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.StockNiveau;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record StockNiveauResponse(
    Long articleId,
    String articleCode,
    String articleLibelle,
    String uniteMesure,
    Long entrepotId,
    String entrepotCode,
    BigDecimal quantite,
    BigDecimal valeurTotale,
    BigDecimal coutMoyen,
    BigDecimal stockMin,
    boolean sousSeuil
) {
    public static StockNiveauResponse from(StockNiveau n) {
        var art = n.getArticle();
        var ent = n.getEntrepot();
        BigDecimal qte = n.getQuantite() == null ? BigDecimal.ZERO : n.getQuantite();
        BigDecimal valeur = n.getValeurTotale() == null ? BigDecimal.ZERO : n.getValeurTotale();
        BigDecimal cmp = qte.signum() == 0
            ? BigDecimal.ZERO
            : valeur.divide(qte, 2, RoundingMode.HALF_UP);
        BigDecimal stockMin = art.getStockMin() == null ? BigDecimal.ZERO : art.getStockMin();
        return new StockNiveauResponse(
            art.getId(),
            art.getCode(),
            art.getLibelle(),
            art.getUniteMesure(),
            ent.getId(),
            ent.getCode(),
            qte,
            valeur,
            cmp,
            stockMin,
            qte.compareTo(stockMin) < 0
        );
    }
}

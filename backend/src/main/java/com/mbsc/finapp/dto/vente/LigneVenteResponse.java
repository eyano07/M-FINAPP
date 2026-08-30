package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.enums.TypeArticle;

import java.math.BigDecimal;

public record LigneVenteResponse(
    Long id,
    Long articleId,
    String articleCode,
    String designation,
    TypeArticle type,
    BigDecimal quantite,
    BigDecimal prixUnitaire,
    boolean soumisTva,
    BigDecimal montantHt,
    BigDecimal montantTva,
    BigDecimal montantTtc
) {
    public static LigneVenteResponse from(LigneVente l) {
        var article = l.getArticle();
        BigDecimal ht = l.getMontantHt() == null ? BigDecimal.ZERO : l.getMontantHt();
        BigDecimal tva = l.getMontantTva() == null ? BigDecimal.ZERO : l.getMontantTva();
        return new LigneVenteResponse(
            l.getId(),
            article == null ? null : article.getId(),
            article == null ? null : article.getCode(),
            l.getDesignation(),
            article == null ? null : article.getType(),
            l.getQuantite(),
            l.getPrixUnitaire(),
            l.isSoumisTva(),
            ht,
            tva,
            ht.add(tva)
        );
    }
}

package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.LigneMouvementStock;

import java.math.BigDecimal;

public record LigneMouvementResponse(
    Long id,
    Long articleId,
    String articleCode,
    String articleLibelle,
    Long entrepotSourceId,
    String entrepotSourceCode,
    Long entrepotCibleId,
    String entrepotCibleCode,
    BigDecimal quantite,
    BigDecimal coutUnitaire,
    BigDecimal montant
) {
    public static LigneMouvementResponse from(LigneMouvementStock l) {
        var art = l.getArticle();
        var src = l.getEntrepotSource();
        var cib = l.getEntrepotCible();
        return new LigneMouvementResponse(
            l.getId(),
            art == null ? null : art.getId(),
            art == null ? null : art.getCode(),
            art == null ? null : art.getLibelle(),
            src == null ? null : src.getId(),
            src == null ? null : src.getCode(),
            cib == null ? null : cib.getId(),
            cib == null ? null : cib.getCode(),
            l.getQuantite(),
            l.getCoutUnitaire(),
            l.getMontant()
        );
    }
}

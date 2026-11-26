package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.LotStock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Un lot de stock (date d'achat, fournisseur, prix) encore actif, pour les
 * pages de stock par lot du module Restaurant — suivi de gestion, voir
 * {@code LotStockService}. {@code fournisseur} est {@code null} si le lot ne
 * vient pas d'un achat par note de frais (réception directe, lot d'ouverture).
 */
public record LotStockResponse(
    Long id,
    Long articleId,
    String articleCode,
    String articleLibelle,
    String uniteMesure,
    String entrepotCode,
    LocalDate dateEntree,
    String fournisseur,
    BigDecimal quantiteRestante,
    BigDecimal prixAchatUnitaire,
    BigDecimal prixTransportUnitaire,
    BigDecimal coutUnitaire,
    BigDecimal valeur
) {
    public static LotStockResponse from(LotStock l) {
        BigDecimal cout = l.getPrixAchatUnitaire().add(l.getPrixTransportUnitaire());
        return new LotStockResponse(
            l.getId(),
            l.getArticle().getId(),
            l.getArticle().getCode(),
            l.getArticle().getLibelle(),
            l.getArticle().getUniteMesure(),
            l.getEntrepot().getCode(),
            l.getDateEntree(),
            l.getFournisseur(),
            l.getQuantiteRestante(),
            l.getPrixAchatUnitaire(),
            l.getPrixTransportUnitaire(),
            cout,
            cout.multiply(l.getQuantiteRestante()).setScale(2, RoundingMode.HALF_UP)
        );
    }
}

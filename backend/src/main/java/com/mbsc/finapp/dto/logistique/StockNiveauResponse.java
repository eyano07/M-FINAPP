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
    /** Valeur hors frais d'approche (transport, manutention) — voir StockNiveau.valeurAchat. */
    BigDecimal valeurAchat,
    /** Prix d'achat moyen hors frais : coutMoyen le complète des frais d'approche. */
    BigDecimal prixAchatMoyen,
    BigDecimal stockMin,
    boolean sousSeuil,
    /** Quantité mise de côté par les commandes en attente (ventes BROUILLON, V134) ; 0 hors carte du restaurant. */
    BigDecimal quantiteReservee,
    /** Ce qui peut encore être vendu ou sorti : quantité moins le réservé. */
    BigDecimal quantiteDisponible
) {
    /** La même ligne, avec ce que les commandes en attente ont mis de côté. */
    public StockNiveauResponse avecReserve(BigDecimal reserve) {
        BigDecimal r = reserve == null ? BigDecimal.ZERO : reserve;
        return new StockNiveauResponse(articleId, articleCode, articleLibelle, uniteMesure, entrepotId, entrepotCode, quantite,
            valeurTotale, coutMoyen, valeurAchat, prixAchatMoyen, stockMin, sousSeuil, r, quantite.subtract(r));
    }

    public static StockNiveauResponse from(StockNiveau n) {
        var art = n.getArticle();
        var ent = n.getEntrepot();
        BigDecimal qte = n.getQuantite() == null ? BigDecimal.ZERO : n.getQuantite();
        BigDecimal valeur = n.getValeurTotale() == null ? BigDecimal.ZERO : n.getValeurTotale();
        // 6 décimales, comme le CMP interne de StockService : arrondi au
        // centime de dollar, un coût unitaire perdait jusqu'à ~11 FC une
        // fois reconverti en francs (1,36 $ × 2 200 = 2 992 FC pour une
        // bouteille achetée 3 000 FC), et ne se recoupait plus avec la
        // valeur du stock affichée sur la même ligne.
        BigDecimal cmp = qte.signum() == 0
            ? BigDecimal.ZERO
            : valeur.divide(qte, 6, RoundingMode.HALF_UP);
        BigDecimal valeurAchat = n.getValeurAchat() == null ? valeur : n.getValeurAchat();
        BigDecimal prixAchat = qte.signum() == 0
            ? BigDecimal.ZERO
            : valeurAchat.divide(qte, 6, RoundingMode.HALF_UP);
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
            valeurAchat,
            prixAchat,
            stockMin,
            qte.compareTo(stockMin) < 0,
            BigDecimal.ZERO,
            qte
        );
    }
}

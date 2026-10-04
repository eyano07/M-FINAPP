package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.StockGrandLivre;
import com.mbsc.finapp.domain.enums.StatutMouvement;

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
    BigDecimal valeurApres,
    Long mouvementId,
    StatutMouvement mouvementStatut,
    boolean annulable
) {
    public static StockGrandLivreResponse from(StockGrandLivre s) {
        return from(s, false);
    }

    /**
     * @param annulable true si l'écran qui affiche cette ligne sait annuler
     *        son mouvement — voir RestaurantService.grandLivreProvisions
     */
    public static StockGrandLivreResponse from(StockGrandLivre s, boolean annulable) {
        var mouvement = s.getMouvement();
        return new StockGrandLivreResponse(
            s.getId(),
            s.getDateEcriture(),
            s.getArticle() == null ? null : s.getArticle().getCode(),
            s.getEntrepot() == null ? null : s.getEntrepot().getCode(),
            mouvement == null ? null : mouvement.getReference(),
            s.getQteEntree(),
            s.getQteSortie(),
            s.getQteApres(),
            s.getValeurUnitaire(),
            s.getValeurApres(),
            mouvement == null ? null : mouvement.getId(),
            mouvement == null ? null : mouvement.getStatut(),
            annulable
        );
    }
}

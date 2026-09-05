package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CamionMineraiResponse(
    Long id,
    Long articleId,
    String articleCode,
    String articleLibelle,
    Long entrepotId,
    String entrepotNom,
    String plaque,
    LocalDate dateAchat,
    BigDecimal prixAchat,
    /** Prix d'achat + frais accessoires incorpores : ce qui sortira du stock a la vente. */
    BigDecimal coutAcquisition,
    StatutCamionMinerai statut,
    boolean regle,
    String mouvementReference,
    String pieceReceptionReference,
    String transactionReglementReference
) {
    public static CamionMineraiResponse from(CamionMinerai c) {
        return new CamionMineraiResponse(
            c.getId(),
            c.getArticle().getId(),
            c.getArticle().getCode(),
            c.getArticle().getLibelle(),
            c.getEntrepot().getId(),
            c.getEntrepot().getNom(),
            c.getPlaque(),
            c.getDateAchat(),
            c.getPrixAchat(),
            c.getCoutAcquisition(),
            c.getStatut(),
            c.isRegle(),
            c.getMouvement() == null ? null : c.getMouvement().getReference(),
            c.getPieceReception() == null ? null : c.getPieceReception().getReference(),
            c.getTransactionReglement() == null ? null : c.getTransactionReglement().getReference()
        );
    }
}

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
    LocalDate dateReception,
    /** Prix hors taxes propose par la logistique (A_VALIDER) ou constate a la validation (EN_STOCK/VENDU). */
    BigDecimal prixAchat,
    /** TVA recuperable (4452) : nulle tant que l'achat n'est pas valide (la TVA reelle depend du taux au paiement). */
    BigDecimal montantTva,
    /** Dette envers le fournisseur (prix hors taxes + TVA) : reelle si l'achat est valide,
     *  sinon estimee au taux de TVA du jour de reception — voir MineraiService.detteEstimee. */
    BigDecimal detteFournisseur,
    /** Prix d'achat + frais accessoires incorpores : ce qui sortira du stock a la vente. */
    BigDecimal coutAcquisition,
    /** Total des frais accessoires du camion, postes ou encore en attente de validation. */
    BigDecimal totalFraisConnexes,
    StatutCamionMinerai statut,
    boolean regle,
    String mouvementReference,
    String pieceReceptionReference,
    String transactionReglementReference,
    /** Reference de la note de reglement en cours (circuit DFIN/DA/Tresorerie), nulle si aucune. */
    String noteFraisReglementReference
) {
    public static CamionMineraiResponse from(CamionMinerai c) {
        return from(c, BigDecimal.ZERO, c.detteFournisseur());
    }

    public static CamionMineraiResponse from(CamionMinerai c, BigDecimal totalFraisConnexes) {
        return from(c, totalFraisConnexes, c.detteFournisseur());
    }

    public static CamionMineraiResponse from(CamionMinerai c, BigDecimal totalFraisConnexes, BigDecimal detteFournisseur) {
        return new CamionMineraiResponse(
            c.getId(),
            c.getArticle().getId(),
            c.getArticle().getCode(),
            c.getArticle().getLibelle(),
            c.getEntrepot().getId(),
            c.getEntrepot().getNom(),
            c.getPlaque(),
            c.getDateReception(),
            c.getPrixAchat(),
            c.getMontantTva(),
            detteFournisseur == null ? c.detteFournisseur() : detteFournisseur,
            c.getCoutAcquisition(),
            totalFraisConnexes == null ? BigDecimal.ZERO : totalFraisConnexes,
            c.getStatut(),
            c.isRegle(),
            c.getMouvement() == null ? null : c.getMouvement().getReference(),
            c.getPieceReception() == null ? null : c.getPieceReception().getReference(),
            c.getTransactionReglement() == null ? null : c.getTransactionReglement().getReference(),
            c.getNoteFraisReglement() == null ? null : c.getNoteFraisReglement().getReference()
        );
    }
}

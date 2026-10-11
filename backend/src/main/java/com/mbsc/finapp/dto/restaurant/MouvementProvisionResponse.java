package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne de l'historique des provisions : une ligne du grand livre de stock, avec le type et l'observation de la
 * sortie quand c'en est une saisie depuis l'écran Entrées / sorties de provisions.
 *
 * @param annulable   true si l'écran sait annuler le mouvement de cette ligne
 * @param typeSortie  nom du {@code TypeSortieProvision} ; null pour une entrée, une sortie d'une production ou une
 *                    sortie saisie avant la création des types
 * @param observation détail saisi avec la sortie
 */
public record MouvementProvisionResponse(
    Long id,
    LocalDate dateEcriture,
    String articleCode,
    String articleLibelle,
    String entrepotCode,
    String mouvementReference,
    BigDecimal qteEntree,
    BigDecimal qteSortie,
    BigDecimal qteApres,
    BigDecimal valeurUnitaire,
    BigDecimal valeurApres,
    Long mouvementId,
    StatutMouvement mouvementStatut,
    boolean annulable,
    String typeSortie,
    String observation
) {
    public static MouvementProvisionResponse from(StockGrandLivreResponse ligne, String typeSortie, String observation) {
        return new MouvementProvisionResponse(
            ligne.id(), ligne.dateEcriture(), ligne.articleCode(), ligne.articleLibelle(), ligne.entrepotCode(),
            ligne.mouvementReference(), ligne.qteEntree(), ligne.qteSortie(), ligne.qteApres(),
            ligne.valeurUnitaire(), ligne.valeurApres(), ligne.mouvementId(), ligne.mouvementStatut(),
            ligne.annulable(), typeSortie, observation);
    }
}

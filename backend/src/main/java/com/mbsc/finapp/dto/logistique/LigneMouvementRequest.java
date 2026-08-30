package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Une ligne d'un mouvement de stock.
 *
 * @param coutUnitaire cout d'entree unitaire. Contraint a etre positif ou nul :
 *        sans cette validation, un cout negatif etait accepte et enregistre en
 *        brouillon (la table ne porte aucune contrainte), puis la validation du
 *        mouvement produisait une ecriture de montant negatif que la contrainte
 *        chk_grand_livre_montants_positifs rejetait en base — soit une erreur
 *        500 opaque et un mouvement definitivement bloque, impossible a valider
 *        comme a corriger.
 */
public record LigneMouvementRequest(
    @NotNull Long articleId,
    Long entrepotSourceId,
    Long entrepotCibleId,
    @NotNull @Positive BigDecimal quantite,
    @PositiveOrZero BigDecimal coutUnitaire
) {}

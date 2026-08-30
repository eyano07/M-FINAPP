package com.mbsc.finapp.dto.vente;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * @param prixUnitaire prix hors taxes dans la devise de saisie de la vente ;
 *                     toujours fourni par le client (article.prixVente est en
 *                     FC, une convention independante de la devise de base,
 *                     donc le backend ne peut pas le reconvertir lui-meme).
 */
public record LigneVenteRequest(

    @NotNull(message = "L'article est obligatoire")
    Long articleId,

    @NotNull(message = "La quantite est obligatoire")
    @Positive(message = "La quantite doit etre strictement positive")
    BigDecimal quantite,

    @NotNull(message = "Le prix unitaire est obligatoire")
    @PositiveOrZero(message = "Le prix unitaire ne peut pas etre negatif")
    BigDecimal prixUnitaire
) {}

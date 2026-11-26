package com.mbsc.finapp.dto.vente;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Ligne ajoutee a une vente BROUILLON deja enregistree (voir
 * VenteService.ajouterLigne). Memes champs que {@link LigneVenteRequest},
 * plus {@code entrepotId} : la vente d'origine peut n'avoir porte que des
 * services et n'avoir donc jamais precise d'entrepot, alors que la ligne
 * ajoutee peut etre un article stocke (ex. une boisson supplementaire).
 */
public record AjouterLigneVenteRequest(

    @NotNull(message = "L'article est obligatoire")
    Long articleId,

    @NotNull(message = "La quantite est obligatoire")
    @Positive(message = "La quantite doit etre strictement positive")
    BigDecimal quantite,

    @NotNull(message = "Le prix unitaire est obligatoire")
    @PositiveOrZero(message = "Le prix unitaire ne peut pas etre negatif")
    BigDecimal prixUnitaire,

    Long camionId,

    Long entrepotId
) {}

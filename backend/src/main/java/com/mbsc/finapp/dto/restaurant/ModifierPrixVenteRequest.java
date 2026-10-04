package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Ajustement du prix de vente d'un plat depuis sa fiche technique. */
public record ModifierPrixVenteRequest(
    @PositiveOrZero(message = "Le prix de vente ne peut pas être négatif")
    BigDecimal prixVente
) {}

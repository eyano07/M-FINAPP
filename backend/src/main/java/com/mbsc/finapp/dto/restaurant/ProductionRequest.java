package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Production de portions d'un plat a partir de provisions.
 *
 * @param quantite       nombre de portions produites.
 * @param lignes         ingredients REELLEMENT consommes, en quantite totale
 *                       (deja multipliee par le nombre de portions cote client).
 *                       Pre-remplies depuis la fiche technique mais ajustables :
 *                       c'est cette liste qui fait foi sur le stock, jamais la
 *                       fiche. Au moins une ligne — une production sans
 *                       ingredient ne pourrait produire aucune ecriture.
 * @param dateProduction aujourd'hui par defaut.
 */
public record ProductionRequest(
    @NotNull Long platId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull Long entrepotId,
    LocalDate dateProduction,
    @NotEmpty(message = "Indiquez au moins un ingredient consomme")
    @Valid List<LigneProductionRequest> lignes
) {}

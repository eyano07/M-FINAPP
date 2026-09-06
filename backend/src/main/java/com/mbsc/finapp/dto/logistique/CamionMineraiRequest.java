package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reception d'un chargement de minerais par la logistique — un simple
 * constat d'arrivee, pas un achat : voir {@code CamionMinerai}. Le caissier
 * validera ensuite ce prix pour qu'il devienne l'achat effectif.
 *
 * @param prixAchat prix hors taxes propose pour le chargement entier, en
 *                  devise de base. Tous les camions d'un meme minerais
 *                  partagent ce prix en pratique, mais il reste saisi a
 *                  chaque reception : rien n'interdit qu'il change d'un
 *                  arrivage a l'autre. Le caissier peut le corriger a la
 *                  validation si le prix reellement facture differe.
 */
public record CamionMineraiRequest(
    @NotNull Long articleId,
    @NotNull Long entrepotId,
    @NotBlank @Size(max = 40) String plaque,
    @NotNull LocalDate dateReception,
    @NotNull @Positive BigDecimal prixAchat
) {}

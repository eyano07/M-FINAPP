package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reception d'un chargement de minerais par la logistique.
 *
 * @param prixAchat prix d'achat du chargement entier, en devise de base. Tous
 *                  les camions d'un meme minerais partagent ce prix en
 *                  pratique, mais il reste saisi a chaque reception : rien
 *                  n'interdit qu'il change d'un arrivage a l'autre.
 */
public record CamionMineraiRequest(
    @NotNull Long articleId,
    @NotNull Long entrepotId,
    @NotBlank @Size(max = 40) String plaque,
    @NotNull LocalDate dateAchat,
    @NotNull @Positive BigDecimal prixAchat
) {}

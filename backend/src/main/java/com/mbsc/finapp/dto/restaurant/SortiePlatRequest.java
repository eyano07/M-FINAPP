package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sortie de stock d'un plat hors vente (périmé, moisi, renversé, offert...).
 *
 * @param precision   obligatoire pour le motif AUTRE, facultative sinon
 * @param dateSortie  aujourd'hui si absente
 */
public record SortiePlatRequest(
    @NotNull Long articleId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull Long entrepotId,
    @NotNull MotifSortiePlat motif,
    @Size(max = 500) String precision,
    LocalDate dateSortie
) {}

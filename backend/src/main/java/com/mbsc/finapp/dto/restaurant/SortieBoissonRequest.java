package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.MotifSortieBoisson;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Perte d'une boisson sans consigne (casse, péremption, cadeau).
 *
 * @param precision  facultative (ex. « bouteille tombée au service »)
 * @param dateSortie aujourd'hui si absente
 */
public record SortieBoissonRequest(
    @NotNull Long articleId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull Long entrepotId,
    @NotNull MotifSortieBoisson motif,
    @Size(max = 500) String precision,
    LocalDate dateSortie
) {}

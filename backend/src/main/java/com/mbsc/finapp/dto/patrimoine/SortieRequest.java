package com.mbsc.finapp.dto.patrimoine;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sortie d'un bien du patrimoine.
 *
 * @param valeurCession prix de cession ; 0 ou absent vaut mise au rebut
 * @param compteContrepartieNumero ou encaisser le prix (caisse, banque, client)
 */
public record SortieRequest(
    @NotNull LocalDate dateSortie,
    @PositiveOrZero BigDecimal valeurCession,
    String compteContrepartieNumero
) {}

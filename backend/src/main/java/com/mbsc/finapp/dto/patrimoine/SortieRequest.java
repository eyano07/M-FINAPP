package com.mbsc.finapp.dto.patrimoine;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sortie d'un bien du patrimoine.
 *
 * @param valeurCession prix de cession ; 0 ou absent vaut mise au rebut
 * @param compteContrepartieNumero créance sur le cessionnaire, 4851 par défaut ; jamais un compte de trésorerie :
 *                                 l'encaissement du prix passe par la caisse ou la banque (audit SYSCOHADA C-08)
 * @param typeCession cession courante (654/754) ou hors activités ordinaires (812/822, par défaut)
 */
public record SortieRequest(
    @NotNull LocalDate dateSortie,
    @PositiveOrZero BigDecimal valeurCession,
    String compteContrepartieNumero,
    com.mbsc.finapp.domain.enums.TypeCession typeCession
) {}

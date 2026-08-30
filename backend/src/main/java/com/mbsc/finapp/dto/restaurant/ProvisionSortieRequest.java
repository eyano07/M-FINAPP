package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sortie d'une provision : utilisation en cuisine, casse ou péremption. Un
 * seul type générique avec motif libre — contrairement aux boissons, une
 * provision n'a pas de compteur de vides à distinguer, la seule variable est
 * la raison de la sortie, qui n'a pas d'incidence sur le traitement.
 */
public record ProvisionSortieRequest(
    @NotNull Long articleId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull Long entrepotId,
    @Size(max = 500) String motif,
    LocalDate dateMouvement
) {}

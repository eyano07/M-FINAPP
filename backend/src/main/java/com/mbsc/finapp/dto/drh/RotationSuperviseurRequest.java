package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * {@code prestationFin}/{@code reposDebut}/{@code reposFin} sont optionnels :
 * si absents, le service les calcule depuis {@code prestationDebut} (14 j de
 * prestation puis 7 j de repos) — équivalent du bouton "Calculer 14j + 7j"
 * de l'ancien outil. Fournis, ils priment (ajustement manuel).
 */
public record RotationSuperviseurRequest(
    @NotNull Long employeId,
    @NotNull Long siteId,
    @NotNull Integer annee,
    @NotNull Integer mois,
    @NotNull LocalDate prestationDebut,
    LocalDate prestationFin,
    LocalDate reposDebut,
    LocalDate reposFin,
    @Size(max = 500) String notes
) {}

package com.mbsc.finapp.dto.rapprochement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Relevé d'un établissement pour un mois, saisi ou issu de l'extraction par l'IA puis relu. */
public record ReleveRequest(
    @NotNull Long etablissementId,
    @NotNull @Min(1) @Max(12) Integer mois,
    @NotNull @Min(2000) @Max(2100) Integer annee,
    @NotNull BigDecimal soldeOuverture,
    @NotNull BigDecimal soldeCloture,
    @Size(max = 255) String source,
    @NotNull @Valid List<LigneReleveRequest> lignes
) {}

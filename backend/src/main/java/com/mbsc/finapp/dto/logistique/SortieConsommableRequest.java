package com.mbsc.finapp.dto.logistique;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sortie de consommables (19/11/2026) : remise de fournitures à un service ou à une personne. Le mouvement de SORTIE
 * est créé et validé d'un coup ; chaque ligne passe en charge sur le compte de l'article, au coût moyen.
 */
public record SortieConsommableRequest(
    @NotNull LocalDate dateSortie,
    @NotBlank @Size(max = 120) String beneficiaire,
    @Size(max = 200) String motif,
    @NotEmpty @Valid List<Ligne> lignes
) {
    public record Ligne(@NotNull Long articleId, @NotNull Long entrepotId, @NotNull @Positive BigDecimal quantite) { }
}

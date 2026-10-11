package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.TypeSortieProvision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sortie d'une provision : utilisation en cuisine, casse, péremption... Le type se choisit dans une liste
 * fermée ({@link TypeSortieProvision}) ; contrairement aux boissons, une provision n'a pas de compteur de
 * vides à distinguer, et le type n'a pas d'incidence sur le traitement : il sert à savoir où partent les
 * provisions.
 *
 * @param typeSortie  raison de la sortie
 * @param observation détail libre ; obligatoire pour le type AUTRE, facultatif sinon
 */
public record ProvisionSortieRequest(
    @NotNull Long articleId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull Long entrepotId,
    @NotNull TypeSortieProvision typeSortie,
    @Size(max = 500) String observation,
    LocalDate dateMouvement
) {}

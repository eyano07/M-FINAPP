package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Saisie manuelle d'un mouvement du stock de vides (casse, perime, ajustement
 * d'inventaire). Les types VENTE et RETOUR_VENTE sont refuses : ils sont
 * generes automatiquement par la validation ou l'annulation d'une vente.
 *
 * <p>La quantite est toujours POSITIVE : c'est le type qui porte le sens.</p>
 *
 * @param entrepotId obligatoire pour CASSE_PLEINE et PERIME : ces deux types
 *        font aussi sortir la boisson de son propre stock (avec ecriture
 *        comptable), qui se tient par entrepot. Ignore pour les autres types.
 */
public record MouvementEmballageRequest(
    @NotNull Long emballageId,
    @NotNull TypeMouvementEmballage type,
    @NotNull @Positive Integer quantite,
    LocalDate dateMouvement,
    @Size(max = 500) String motif,
    Long entrepotId
) {}

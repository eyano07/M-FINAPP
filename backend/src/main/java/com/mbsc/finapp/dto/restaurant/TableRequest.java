package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.FormeTable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param id         null pour une nouvelle table ; l'identifiant d'une table
 *                   existante de la salle, pour la mettre a jour. Voir
 *                   {@code RestaurantService.enregistrerPlan} : toute table de
 *                   la salle absente de la liste envoyee est supprimee.
 * @param nbChaises  nombre de chaises affichees autour de la table.
 */
public record TableRequest(
    Long id,
    @NotBlank String numero,
    FormeTable forme,
    int posX,
    int posY,
    int largeur,
    int hauteur,
    @PositiveOrZero Integer nbChaises
) {}

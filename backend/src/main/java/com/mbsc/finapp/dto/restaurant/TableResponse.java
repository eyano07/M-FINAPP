package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.TableRestaurant;
import com.mbsc.finapp.domain.enums.FormeTable;

/**
 * @param aCommandeNonPayee au moins une vente VALIDEE liee a cette table n'est
 *                          pas encore reglee (voir Vente.estReglee())
 * @param aCommandePayee    aucune commande non payee, mais au moins une vente
 *                          VALIDEE liee et reglee — la table reste occupee
 *                          jusqu'a liberation manuelle (voir RestaurantService)
 */
public record TableResponse(
    Long id,
    String numero,
    FormeTable forme,
    int posX,
    int posY,
    int largeur,
    int hauteur,
    int nbChaises,
    boolean occupee,
    boolean aCommandeNonPayee,
    boolean aCommandePayee
) {
    /** Sans les badges de commande — utilise quand on vient d'ecrire le plan lui-meme, pas les ventes. */
    public static TableResponse from(TableRestaurant t) {
        return from(t, false, false);
    }

    public static TableResponse from(TableRestaurant t, boolean aCommandeNonPayee, boolean aCommandePayee) {
        return new TableResponse(
            t.getId(), t.getNumero(), t.getForme(),
            t.getPosX(), t.getPosY(), t.getLargeur(), t.getHauteur(),
            t.getNbChaises(), t.isOccupee(),
            aCommandeNonPayee, aCommandePayee
        );
    }
}

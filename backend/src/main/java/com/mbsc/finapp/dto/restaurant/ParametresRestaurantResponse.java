package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;

import java.time.Instant;

/**
 * @param retraitPerimesMode      comment les plats périmés quittent le stock (manuel avec notification, ou automatique)
 * @param retraitPerimesPar       administrateur qui a activé le retrait automatique (les retraits sont passés à son nom) ;
 *                                null en mode manuel, ou si cet administrateur n'existe plus
 * @param retraitPerimesParActif  n'a de sens qu'en mode automatique : false quand cet administrateur n'existe plus, est
 *                                désactivé ou n'est plus administrateur, ce qui suspend le retrait automatique (la tâche
 *                                prévient alors comme en mode manuel) jusqu'à ce qu'un administrateur reconfirme le mode
 * @param retraitPerimesLe        date du dernier changement de mode
 */
public record ParametresRestaurantResponse(
    Devise deviseAffichage,
    ModeRetraitPerimes retraitPerimesMode,
    String retraitPerimesPar,
    boolean retraitPerimesParActif,
    Instant retraitPerimesLe
) {
    public static ParametresRestaurantResponse from(ParametresRestaurant p) {
        User auteur = p.getRetraitPerimesAuteur();
        return new ParametresRestaurantResponse(
            p.getDeviseAffichage(),
            p.getRetraitPerimesMode(),
            auteur == null ? null : nomComplet(auteur),
            p.retraitPerimesAuteurHabilite(),
            p.getRetraitPerimesModifieLe());
    }

    private static String nomComplet(User u) {
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}

package com.mbsc.finapp.domain.enums;

/**
 * Cycle de vie d'un camion de minerais. Un camion se vend entier : il n'y a
 * pas d'etat intermediaire de vente partielle.
 */
public enum StatutCamionMinerai {
    /** Receptionne par la logistique, en stock, disponible a la vente. */
    EN_STOCK,
    /** Cede sur une vente validee, sorti du stock a son cout d'achat. */
    VENDU
}

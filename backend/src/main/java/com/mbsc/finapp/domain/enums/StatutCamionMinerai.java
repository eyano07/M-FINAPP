package com.mbsc.finapp.domain.enums;

/**
 * Cycle de vie d'un camion de minerais. Un camion se vend entier : il n'y a
 * pas d'etat intermediaire de vente partielle.
 */
public enum StatutCamionMinerai {
    /**
     * Receptionne par la logistique : plaque, date et prix proposes, mais
     * aucun achat encore constate — ni ecriture, ni entree en stock. Dans cet
     * etat, le camion n'est ni vendable ni modifiable par des frais
     * accessoires (rien a incorporer tant que le stock n'existe pas).
     */
    A_VALIDER,
    /** Achat valide par le caissier (voir MineraiService.validerAchat) : en stock, disponible a la vente. */
    EN_STOCK,
    /** Cede sur une vente validee, sorti du stock a son cout d'acquisition. */
    VENDU
}

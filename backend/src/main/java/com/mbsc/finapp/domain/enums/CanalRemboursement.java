package com.mbsc.finapp.domain.enums;

/**
 * Canal du remboursement d'un retour de vente : la caisse, une banque ou un operateur de mobile money (l'argent sort),
 * ou la CREANCE d'une vente a credit pas encore reglee (la dette du client diminue, aucun argent ne sort).
 */
public enum CanalRemboursement {
    CAISSE,
    BANQUE,
    MOBILE_MONEY,
    CREANCE
}

package com.mbsc.finapp.domain.enums;

/** Cycle de vie d'une vente. */
public enum StatutVente {
    /** Saisie en cours : aucune ecriture, aucun mouvement de stock. */
    BROUILLON,
    /** Comptabilisee : stock decremente et pieces generees. */
    VALIDEE,
    /** Extournee : ecritures contre-passees et stock reintegre. */
    ANNULEE
}

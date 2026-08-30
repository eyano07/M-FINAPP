package com.mbsc.finapp.domain.enums;

/**
 * Mode de reglement d'une vente : determine la contrepartie debitee.
 *
 * <p>{@link #CREDIT} debite le compte client (creance) ; les autres
 * debitent directement le compte de tresorerie concerne.</p>
 */
public enum ModeReglement {
    /** A credit : creance client (4111), encaissee ulterieurement. */
    CREDIT,
    /** Comptant en especes : compte caisse (571). */
    CAISSE,
    /** Comptant par banque : compte de l'etablissement bancaire choisi. */
    BANQUE,
    /** Comptant par mobile money : compte de l'operateur choisi. */
    MOBILE_MONEY
}

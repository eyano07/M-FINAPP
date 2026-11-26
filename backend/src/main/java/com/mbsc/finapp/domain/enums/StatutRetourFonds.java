package com.mbsc.finapp.domain.enums;

/**
 * Etat d'une demande de retour de fonds sur une note de frais deja payee (voir {@code RetourFondsService}).
 */
public enum StatutRetourFonds {
    /** Demandee par l'emetteur de la note : en attente de l'argent a la caisse. */
    EN_ATTENTE,
    /** Le caissier a recu l'argent : retour en caisse, stock et note mis a jour. */
    VALIDE,
    /** Le caissier n'a pas recu l'argent (motif obligatoire) : la note reste telle quelle. */
    REFUSE,
    /** Retiree par l'emetteur avant la decision du caissier. */
    ANNULE
}

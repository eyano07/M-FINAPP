package com.mbsc.finapp.domain.enums;

/**
 * Nature d'une note de frais. Les notes PAIE et IMPOT_PAIE sont créées par le module DRH
 * (voir {@code PaieNoteService}) : leurs comptes sont imposés par le système et ne se modifient pas.
 */
public enum CategorieNote {
    /** Note saisie librement (dépense, achat, encaissement). */
    STANDARD,
    /** Règlement des salaires nets d'un mois ; son paiement écrit la constatation de la paie. */
    PAIE,
    /** Versement d'un impôt ou d'une cotisation sur salaires d'un mois (voir {@link OrganismePaie}). */
    IMPOT_PAIE
}

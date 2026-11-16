package com.mbsc.finapp.domain.enums;

/**
 * Journaux comptables (inspiré ERPNext Journal Entry).
 */
public enum JournalComptable {
    OPERATIONS_DIVERSES,
    CAISSE,
    BANQUE,
    MOBILE_MONEY,
    ACHATS,
    VENTES,
    STOCK,
    /** Comptabilité analytique (classe 9) : reflet automatique des ventes par activité, et saisies analytiques manuelles. */
    ANALYTIQUE,
    /** Journal de paie : constatation mensuelle de la paie à sa clôture (audit SYSCOHADA du 10/11/2026, C-14, C-19). */
    PAIE,
    /** Journal des à-nouveaux : reprise des soldes d'ouverture d'un exercice (C-19). */
    A_NOUVEAUX
}

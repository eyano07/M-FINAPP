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
    ANALYTIQUE
}

package com.mbsc.finapp.domain.enums;

/**
 * Categorie d'une notification, utilisee cote frontend pour choisir l'icone
 * associee dans le panneau de notifications.
 */
public enum TypeNotification {
    NOTE_SOUMISE,
    NOTE_VERIFIEE,
    NOTE_VALIDEE,
    NOTE_REJETEE,
    NOTE_TRANSMISE,
    NOTE_PAYEE,
    NOTE_ANNULEE,
    ENCAISSEMENT_CAISSE,
    /** Crédit Anthropic épuisé : les analyses passent sur OpenAI (administrateurs). */
    IA_CREDIT_EPUISE,
    /** Facture normalisée non certifiée (échecs répétés ou refus du dispositif DGI) : administrateurs. */
    FACTURE_NORMALISEE_ECHEC,
    /** Plats périmés encore en stock, à retirer (retrait manuel, ou retrait automatique suspendu ou en échec) : responsable du restaurant et administrateurs. */
    PLATS_PERIMES,
    /** Plats périmés retirés du stock automatiquement : responsable du restaurant et administrateurs. */
    PLATS_PERIMES_RETIRES,
    /** Budget soumis par le DFIN : le DA doit l'approuver ou le rejeter. */
    BUDGET_SOUMIS,
    /** Budget approuvé par le DA : le DFIN peut le mettre en exécution. */
    BUDGET_APPROUVE,
    /** Budget rejeté par le DA (avec son motif) : le DFIN le reprend pour le corriger. */
    BUDGET_REJETE,
    /** Budget mis en exécution : il contrôle désormais les notes de frais de sa période (DA, DG). */
    BUDGET_EN_EXECUTION,
    /** Note de frais validée par le DA alors qu'elle dépasse le budget, est hors budget ou sans budget (DFIN). */
    BUDGET_DEPASSE
}

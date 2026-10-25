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
    BUDGET_DEPASSE,
    /** Retour de fonds demande par l'emetteur d'une note payee : l'argent est attendu a la caisse (caissiers, lien vers la Caisse). */
    RETOUR_FONDS_A_RECEVOIR,
    /** Demande de retour de fonds retiree par l'emetteur avant la caisse : plus rien a recevoir (caissiers). */
    RETOUR_FONDS_RETIRE,
    /** Retour de fonds recu par le caissier : la note payee est reduite (DFIN, comptable, DA, admin, bar/cuisine, emetteur). */
    RETOUR_FONDS_VALIDE,
    /** Retour de fonds refuse par le caissier (avec son motif) : l'emetteur est prevenu. */
    RETOUR_FONDS_REFUSE,
    /** Action faite sur la copie en ligne (validation, nouvelle note...) appliquee par le serveur local : son auteur est prevenu. */
    ACTION_EN_LIGNE_APPLIQUEE,
    /** Action faite sur la copie en ligne refusee par le serveur local (note changee entre-temps, droits...) : son auteur est prevenu. */
    ACTION_EN_LIGNE_REFUSEE,
    /** Retour d'une vente payee demande par le caissier : le responsable du bar, de la cuisine ou du restaurant decide. */
    RETOUR_VENTE_A_APPROUVER,
    /** Retour de vente approuve par le responsable : le caissier peut rembourser le client (caissiers, demandeur). */
    RETOUR_VENTE_APPROUVE,
    /** Retour de vente refuse par le responsable (avec son motif) : caissiers et demandeur. */
    RETOUR_VENTE_REFUSE,
    /** Retour de vente rembourse par le caissier : responsables du bar, de la cuisine ou du restaurant. */
    RETOUR_VENTE_REMBOURSE
}

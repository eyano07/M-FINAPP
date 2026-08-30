package com.mbsc.finapp.domain.enums;

/**
 * Machine a etats du workflow de validation des notes de frais.
 */
public enum StatutNote {
    BROUILLON,          // creee par Directeur / Caissier (urgence)
    SOUMISE,            // envoyee au DFIN
    VERIFIEE_DFIN,      // DFIN verifie la conformite -> DA
    VALIDEE_DA,         // DA valide -> retour DFIN
    REJETEE_DA,         // DA refuse -> retour DFIN pour correction/annulation
    TRANSMISE_CAISSE,   // DFIN transmet a la caisse
    PAYEE,              // Caissier execute le paiement
    ANNULEE
}

package com.mbsc.finapp.domain.enums;

/** Type de papier à en-tête imprimable — voir PapierEnteteService. */
public enum TypePapierEntete {
    /** En-tête générique de l'entreprise (logo, registre, adresse/téléphone/email de la société). */
    GENERAL,
    /** En-tête générique + identité de l'utilisateur courant (nom, fonction, affectation, contacts propres). */
    INDIVIDUEL
}

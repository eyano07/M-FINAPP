package com.mbsc.finapp.domain.enums;

/**
 * Resultat du controle budgetaire d'une depense (ligne de note de frais) ou d'une note entiere
 * (le resultat le plus defavorable de ses lignes). Toute depense non CONFORME exige une
 * justification dans la note.
 */
public enum StatutControleBudget {
    /** Compte couvert par le budget en execution et montant dans le disponible. */
    CONFORME,
    /** Compte couvert, mais le montant depasse le disponible cumule a fin de mois. */
    DEPASSEMENT,
    /** Aucune ligne du budget en execution ne couvre le compte de la depense. */
    HORS_BUDGET,
    /** Aucun budget en execution pour l'exercice de la depense. */
    SANS_BUDGET,
    /** Pas une depense budgetaire : reglement de dette fournisseur, avance, tresorerie, encaissement... */
    NON_CONCERNE;

    /** Rang de gravite : le statut d'une note est celui de sa ligne la plus grave. */
    public int gravite() {
        return switch (this) {
            case NON_CONCERNE -> 0;
            case CONFORME -> 1;
            case DEPASSEMENT -> 2;
            case HORS_BUDGET -> 3;
            case SANS_BUDGET -> 4;
        };
    }

    /** true si la depense doit etre justifiee dans la note (hors budget, depassement, aucun budget). */
    public boolean exigeJustification() {
        return this == DEPASSEMENT || this == HORS_BUDGET || this == SANS_BUDGET;
    }
}

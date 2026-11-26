package com.mbsc.finapp.domain.enums;

/**
 * Etat d'un retour de vente payee (V134) : le caissier le DEMANDE, le responsable du bar, de la cuisine ou du restaurant
 * l'APPROUVE ou le REFUSE, puis le caissier REMBOURSE. Le caissier peut retirer (ANNULE) une demande pas encore
 * remboursee.
 */
public enum StatutRetourVente {
    DEMANDE,
    APPROUVE,
    REFUSE,
    REMBOURSE,
    ANNULE;

    /** true tant que la demande attend une decision ou son remboursement : une seule par vente. */
    public boolean estOuvert() {
        return this == DEMANDE || this == APPROUVE;
    }
}

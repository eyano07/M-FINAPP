package com.mbsc.finapp.domain.enums;

/**
 * Type d'une sortie de stock de provision — voir {@code RestaurantService.enregistrerSortieProvision}.
 * Tous passent la même écriture (la provision quitte le stock au coût moyen pondéré) : le type ne sert
 * qu'à savoir où partent les provisions, et l'observation, libre, en donne le détail.
 */
public enum TypeSortieProvision {
    UTILISATION_CUISINE("Utilisation en cuisine"),
    CASSE("Casse / renversé"),
    PEREMPTION("Périmé"),
    AVARIE("Moisi / avarié"),
    PERTE("Perte / manquant"),
    REPAS_PERSONNEL("Repas du personnel"),
    DON("Don / offert"),
    /** Exige une observation, sans quoi la sortie resterait inexpliquée. */
    AUTRE("Autre");

    private final String libelle;

    TypeSortieProvision(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}

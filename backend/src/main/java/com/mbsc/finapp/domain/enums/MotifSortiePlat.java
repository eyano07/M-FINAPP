package com.mbsc.finapp.domain.enums;

/**
 * Motif d'une sortie de stock de plat hors vente — voir
 * {@code RestaurantService.enregistrerSortiePlat}. Tous passent la même
 * écriture (le plat quitte le stock au coût de production moyen) : le motif
 * ne sert qu'à savoir où partent les plats.
 */
public enum MotifSortiePlat {
    PERIME("Périmé"),
    MOISI("Moisi / avarié"),
    RENVERSE("Renversé / tombé"),
    BRULE("Brûlé / raté"),
    CADEAU("Offert (cadeau)"),
    REPAS_PERSONNEL("Repas du personnel"),
    /** Exige une précision, sans quoi la sortie resterait inexpliquée. */
    AUTRE("Autre");

    private final String libelle;

    MotifSortiePlat(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}

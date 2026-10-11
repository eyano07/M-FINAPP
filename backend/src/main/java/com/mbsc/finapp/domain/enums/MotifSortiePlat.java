package com.mbsc.finapp.domain.enums;

/**
 * Motif d'une sortie de stock de plat hors vente — voir
 * {@code RestaurantService.enregistrerSortiePlat}. Tous passent la même
 * mécanique (le plat quitte le stock au coût de production moyen, crédit du
 * compte de stock du plat 361x) mais chaque motif a son compte de débit
 * ({@code RestaurantService.compteSortiePlat}, créés par V121) : 736.1 à 736.5
 * pour un plat périmé, moisi, renversé, brûlé ou « autre » (variation des stocks
 * de produits finis, à part de la sortie des plats vendus), 6276.2 pour un plat
 * offert, 6617.1 pour un repas du personnel.
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

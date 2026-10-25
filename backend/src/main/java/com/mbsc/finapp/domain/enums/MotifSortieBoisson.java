package com.mbsc.finapp.domain.enums;

/**
 * Motif de la perte d'une boisson sans consigne (whisky, vin, jus en carton...) — voir
 * {@code RestaurantService.enregistrerSortieBoisson}. Les boissons consignées passent, elles, par les mouvements de
 * vides (CASSE_PLEINE, PERIME, CADEAU), qui rendent aussi la bouteille. Le compte débité est celui de la même perte d'une
 * boisson consignée ({@code RestaurantService.compteChargePerte}) : compte de la boisson pour une casse, 6031.2 pour une
 * boisson périmée, 6276.1 pour une boisson offerte.
 */
public enum MotifSortieBoisson {
    CASSE("Casse", TypeMouvementEmballage.CASSE_PLEINE),
    PERIME("Périmée", TypeMouvementEmballage.PERIME),
    CADEAU("Offerte (cadeau)", TypeMouvementEmballage.CADEAU);

    private final String libelle;
    /** La même perte pour une boisson consignée : elle donne le compte débité. */
    private final TypeMouvementEmballage equivalentConsigne;

    MotifSortieBoisson(String libelle, TypeMouvementEmballage equivalentConsigne) {
        this.libelle = libelle;
        this.equivalentConsigne = equivalentConsigne;
    }

    public String libelle() {
        return libelle;
    }

    public TypeMouvementEmballage equivalentConsigne() {
        return equivalentConsigne;
    }
}

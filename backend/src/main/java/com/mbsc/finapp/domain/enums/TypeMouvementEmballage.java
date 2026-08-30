package com.mbsc.finapp.domain.enums;

/**
 * Mouvement du stock de bouteilles vides.
 *
 * <p>Le sens est porte par le type, jamais par le signe de la quantite : une
 * quantite saisie est toujours positive. Inferer le sens d'un signe obligerait
 * chaque appelant a le connaitre, et un oubli passerait silencieusement.</p>
 *
 * <p>Aucun de ces mouvements ne produit d'ecriture comptable : la consigne
 * n'est pas portee au bilan. Seule la reception de boissons, qui est un vrai
 * achat, genere une piece — par le moteur de stock ordinaire.</p>
 */
public enum TypeMouvementEmballage {

    /** Bouteille vendue : le client consomme sur place, la bouteille vide revient en stock. */
    VENTE(1),
    /** Vente annulee : on reprend les vides qu'elle avait fait entrer. */
    RETOUR_VENTE(-1),
    /** Achat de casiers pleins : on rend l'equivalent en vides (echange de consigne). */
    ACHAT(-1),
    /**
     * Casse d'une bouteille VIDE : elle quitte le stock de vides sans
     * contrepartie. Purement quantitatif, comme tout mouvement d'emballage.
     */
    CASSE(-1),
    /**
     * Casse d'une bouteille PLEINE : la boisson n'a jamais ete servie, donc
     * aucune bouteille vide n'a ete produite — au contraire, le conteneur
     * lui-meme est perdu. Decremente a la fois le stock de vides (comme une
     * casse ordinaire) ET le stock de la boisson (avec ecriture comptable,
     * comme une sortie de stock ordinaire — voir
     * {@code RestaurantService.enregistrerMouvement}).
     */
    CASSE_PLEINE(-1),
    /**
     * Boisson perimee : le contenu est jete mais le contenant n'est PAS
     * detruit — sans effet sur le stock de vides (sens nul). Seul le stock de
     * la boisson diminue, avec la meme ecriture comptable qu'une sortie
     * ordinaire.
     */
    PERIME(0),
    /**
     * Boisson offerte (cadeau, promotion) : meme traitement que PERIME — sans
     * effet sur le stock de vides (sens nul), seul le stock de la boisson
     * diminue. Distingue du PERIME par le motif, pas par le mecanisme.
     */
    CADEAU(0),
    /** Ecart d'inventaire en faveur du stock. */
    AJUSTEMENT_PLUS(1),
    /** Ecart d'inventaire en defaveur du stock. */
    AJUSTEMENT_MOINS(-1);

    private final int sens;

    TypeMouvementEmballage(int sens) {
        this.sens = sens;
    }

    /** +1 si le mouvement alimente le stock de vides, -1 s'il le diminue. */
    public int sens() {
        return sens;
    }

    /** Variation signee du stock pour une quantite saisie (toujours positive). */
    public int delta(int quantite) {
        return sens * quantite;
    }
}

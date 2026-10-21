package com.mbsc.finapp.domain.enums;

/**
 * Mouvement du stock de bouteilles vides.
 *
 * <p>Le sens est porte par le type, jamais par le signe de la quantite : une
 * quantite saisie est toujours positive. Inferer le sens d'un signe obligerait
 * chaque appelant a le connaitre, et un oubli passerait silencieusement.</p>
 *
 * <p>Aucun de ces mouvements ne produit d'ecriture pour les bouteilles vides elles-memes : la
 * consigne n'est pas portee au bilan. Les pieces viennent du moteur de stock ordinaire : la
 * reception de boissons, qui est un vrai achat, et les pertes de boisson (casse d'une pleine,
 * peremption, cadeau), qui sortent la boisson du stock au cout moyen.</p>
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
     * Boisson perimee : c'est la boisson qui est perimee, pas la bouteille. Le contenu est jete —
     * la boisson sort du stock au cout moyen, imputee au compte 6031.2 « Boissons perimees » — et la
     * bouteille, videe, est remise dans le stock de vides (+1 par bouteille), comme pour un cadeau ou
     * une vente.
     */
    PERIME(1),
    /**
     * Boisson offerte (cadeau, promotion) : le contenu est consomme, comme pour une
     * vente — la boisson sort du stock au cout moyen, imputee au compte 6276.1 « Boissons
     * offertes a la clientele » — et la bouteille, elle, est remise dans le stock de vides
     * (+1 par bouteille).
     */
    CADEAU(1),
    /** Ecart d'inventaire en faveur du stock. */
    AJUSTEMENT_PLUS(1),
    /** Ecart d'inventaire en defaveur du stock. */
    AJUSTEMENT_MOINS(-1),
    /**
     * Retour de fonds sur un achat de boissons avec echange de consigne (note reduite apres paiement) : le fournisseur
     * rend les vides des bouteilles non livrees ou reprises — le symetrique d'{@link #ACHAT}, +1 par bouteille.
     */
    RETOUR_ACHAT(1);

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

    /**
     * true pour un mouvement saisi a la main sur l'ecran des mouvements, donc
     * annulable depuis cet ecran. VENTE, RETOUR_VENTE, ACHAT et RETOUR_ACHAT
     * sont produits par la vente, le paiement d'une note d'achat ou son retour
     * de fonds, et ne s'annulent qu'avec l'operation qui les a produits.
     */
    public boolean estSaisieManuelle() {
        return this != VENTE && this != RETOUR_VENTE && this != ACHAT && this != RETOUR_ACHAT;
    }

    /**
     * true si le mouvement fait aussi perdre la boisson elle-meme, pas
     * seulement son contenant : il s'accompagne d'une sortie de stock.
     */
    public boolean sortDuStockDeBoisson() {
        return this == CASSE_PLEINE || this == PERIME || this == CADEAU;
    }

    /** true pour un ecart d'inventaire, reserve a l'administrateur. */
    public boolean estAjustement() {
        return this == AJUSTEMENT_PLUS || this == AJUSTEMENT_MOINS;
    }
}

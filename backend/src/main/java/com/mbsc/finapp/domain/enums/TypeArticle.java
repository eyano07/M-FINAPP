package com.mbsc.finapp.domain.enums;

/** Nature d'un article du catalogue. */
public enum TypeArticle {
    /** Bien stocke : la vente decremente le stock et genere un cout des ventes. */
    MARCHANDISE,
    /** Prestation immaterielle : ni stock, ni entrepot, ni cout des ventes. */
    SERVICE,
    /**
     * Bien stocke destine a etre consomme en interne et non vendu : la SORTIE
     * debite le compte de charge et credite le compte de stock, comme pour une
     * marchandise, mais l'article n'est pas proposable a la vente.
     */
    CONSOMMABLE,
    /**
     * Plat de la carte du restaurant. Se comporte comme une MARCHANDISE du
     * point de vue du stock : les portions produites entrent en stock puis en
     * sortent a la consommation.
     *
     * <p>Attention : le cout de revient n'est juste que si l'entree de
     * production porte un cout unitaire. Sans cela le cout moyen pondere vaut
     * zero et la marge affichee est de 100 % — l'ecran de production le
     * rappelle a la saisie.</p>
     */
    PLAT,
    /**
     * Boisson du restaurant : achetee, stockee, revendue. C'est une
     * MARCHANDISE au sens strict, distinguee uniquement pour que le module
     * Restaurant puisse isoler sa carte du reste du catalogue.
     */
    BOISSON,
    /**
     * Vivres, epices, charbon et autres approvisionnements de cuisine du
     * module Restaurant : stockes et consommes en interne (ils entrent dans
     * la preparation des plats) mais jamais vendus directement — jamais
     * "vendable" (voir {@link Article#estVendable()}), comme CONSOMMABLE.
     *
     * <p>Distinct de CONSOMMABLE : celui-ci est deja utilise par le module
     * Patrimoine pour ses propres fournitures. Les melanger ferait apparaitre
     * le charbon de cuisine dans l'ecran Consommables du gestionnaire de
     * patrimoine, et reciproquement — chaque module isole son propre
     * catalogue, meme principe que PLAT/BOISSON vis-a-vis de MARCHANDISE.</p>
     */
    PROVISION;

    /**
     * true si la nature donne lieu a un suivi de stock, donc a une sortie
     * valorisee lors de la vente.
     *
     * <p>Ce predicat existe pour que les services appelants n'enumerent pas
     * les types un par un : un filtre ecrit
     * {@code type == MARCHANDISE} oublie silencieusement tout type ajoute
     * ensuite. Une vente passerait alors normalement, crediterait le compte de
     * produit, mais ne sortirait jamais l'article du stock — aucun cout des
     * ventes, stock durablement surevalue au bilan et marge affichee a 100 %.
     * Seul le SERVICE, immateriel, est hors stock.</p>
     */
    public boolean estStocke() {
        return this != SERVICE;
    }
}

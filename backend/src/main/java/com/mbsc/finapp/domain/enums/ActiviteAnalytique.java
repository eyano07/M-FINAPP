package com.mbsc.finapp.domain.enums;

import com.mbsc.finapp.domain.Article;

/**
 * Activité de la comptabilité analytique (classe 9) à laquelle une vente est reflétée, selon l'article vendu. Chaque activité
 * a son compte de résultat analytique {@link #compte} (regroupement), subdivisé en chiffre d'affaires HT
 * ({@link #compteChiffreAffaires}) et coût des ventes ({@link #compteCout}) : le solde du regroupement est la marge de
 * l'activité. Comptes créés par la migration V129 — voir {@code AnalytiqueVentesService}.
 */
public enum ActiviteAnalytique {
    BAR("981", "Bar (boissons)"),
    CUISINE("982", "Cuisine (plats)"),
    MARCHANDISES("983", "Marchandises"),
    MINERAIS("984", "Minerais"),
    SERVICES("985", "Services");

    private final String compte;
    private final String libelle;

    ActiviteAnalytique(String compte, String libelle) {
        this.compte = compte;
        this.libelle = libelle;
    }

    /** Compte de résultat analytique de l'activité (98x), regroupement de ses deux sous-comptes. */
    public String compte() {
        return compte;
    }

    public String libelle() {
        return libelle;
    }

    /** Sous-compte crédité du chiffre d'affaires HT de l'activité (98x1). */
    public String compteChiffreAffaires() {
        return compte + "1";
    }

    /** Sous-compte débité du coût des ventes de l'activité (98x2). */
    public String compteCout() {
        return compte + "2";
    }

    /**
     * Activité d'un article vendu : un minerais (camion) relève des Minerais quel que soit son type ; sinon la boisson du
     * Bar, le plat (et la provision, qui sert à le produire) de la Cuisine, le service des Services, toute autre marchandise
     * des Marchandises.
     */
    public static ActiviteAnalytique de(Article article) {
        if (article.isMinerais()) {
            return MINERAIS;
        }
        return switch (article.getType()) {
            case BOISSON -> BAR;
            case PLAT, PROVISION -> CUISINE;
            case SERVICE -> SERVICES;
            case MARCHANDISE, CONSOMMABLE -> MARCHANDISES;
        };
    }
}

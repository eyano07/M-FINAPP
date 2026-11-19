package com.mbsc.finapp.domain.enums;

/**
 * Nature d'un bien immobilise. Sert a proposer par defaut le triplet de
 * comptes OHADA adapte (immobilisation / amortissement / dotation) et a
 * regrouper le registre par famille.
 *
 * <p>Les comptes suggeres sont ceux du referentiel SYSCOHADA verifies
 * imputables et actifs : les comptes de regroupement (681, 81, 82) et les
 * comptes desactives (6811) sont volontairement absents, une piece les
 * utilisant serait refusee par {@code ComptabiliteService}.</p>
 */
public enum CategorieImmobilisation {

    LOGICIEL("2131", "2813", "6812", 36),
    BATIMENT("2311", "2831", "6813", 240),
    MATERIEL_INDUSTRIEL("2411", "2841", "6813", 60),
    MATERIEL_BUREAU("2441", "2844", "6813", 60),
    /** Ordinateurs, imprimantes, modems, périphériques (19/11/2026) : compte 2442, trois ans. */
    MATERIEL_INFORMATIQUE("2442", "2844", "6813", 36),
    MOBILIER("2444", "2844", "6813", 120),
    MATERIEL_TRANSPORT("2451", "2845", "6813", 48),
    AUTRE("2441", "2844", "6813", 60);

    private final String compteImmobilisation;
    private final String compteAmortissement;
    private final String compteDotation;
    /**
     * Durée d'usage proposée (mois), selon les taux d'usage admis en SYSCOHADA révisé : logiciels et matériel
     * informatique 3 ans (33,33 %), matériel de bureau et matériel industriel 5 ans (20 %), matériel de transport 4 ans
     * (25 %), mobilier 10 ans (10 %), bâtiments 20 ans (5 %). Modifiable bien par bien.
     */
    private final int dureeMoisParDefaut;

    CategorieImmobilisation(String compteImmobilisation, String compteAmortissement, String compteDotation, int dureeMois) {
        this.compteImmobilisation = compteImmobilisation;
        this.compteAmortissement = compteAmortissement;
        this.compteDotation = compteDotation;
        this.dureeMoisParDefaut = dureeMois;
    }

    public int dureeMoisParDefaut() { return dureeMoisParDefaut; }

    public String compteImmobilisationParDefaut() { return compteImmobilisation; }
    public String compteAmortissementParDefaut() { return compteAmortissement; }
    public String compteDotationParDefaut() { return compteDotation; }
}

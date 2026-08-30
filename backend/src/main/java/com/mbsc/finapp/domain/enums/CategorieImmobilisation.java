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

    LOGICIEL("2131", "2813", "6812"),
    BATIMENT("2311", "2831", "6813"),
    MATERIEL_INDUSTRIEL("2411", "2841", "6813"),
    MATERIEL_BUREAU("2441", "2844", "6813"),
    MOBILIER("2444", "2844", "6813"),
    MATERIEL_TRANSPORT("2451", "2845", "6813"),
    AUTRE("2441", "2844", "6813");

    private final String compteImmobilisation;
    private final String compteAmortissement;
    private final String compteDotation;

    CategorieImmobilisation(String compteImmobilisation, String compteAmortissement, String compteDotation) {
        this.compteImmobilisation = compteImmobilisation;
        this.compteAmortissement = compteAmortissement;
        this.compteDotation = compteDotation;
    }

    public String compteImmobilisationParDefaut() { return compteImmobilisation; }
    public String compteAmortissementParDefaut() { return compteAmortissement; }
    public String compteDotationParDefaut() { return compteDotation; }
}

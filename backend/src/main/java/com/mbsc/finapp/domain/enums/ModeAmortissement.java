package com.mbsc.finapp.domain.enums;

/**
 * Mode de calcul de l'amortissement.
 *
 * <p>Le coefficient degressif est celui du bareme fiscal usuel, indexe sur la
 * duree d'utilisation exprimee en annees : 1,5 jusqu'a 4 ans, 2,0 de 5 a 6 ans,
 * 2,5 au-dela. Il n'est applique qu'au mode {@link #DEGRESSIF} ; le lineaire
 * garde un coefficient de 1 et donc exactement le comportement d'origine.</p>
 */
public enum ModeAmortissement {

    LINEAIRE,

    /**
     * Degressif : la dotation se calcule sur la valeur residuelle et non sur
     * la base d'origine, jusqu'a ce que l'annuite lineaire du temps restant
     * devienne plus avantageuse — voir {@code PatrimoineService.construirePlan}.
     */
    DEGRESSIF;

    /** Coefficient degressif applicable a une duree exprimee en mois. */
    public static double coefficient(int dureeMois) {
        int annees = Math.max(1, (int) Math.round(dureeMois / 12.0));
        if (annees <= 4) return 1.5;
        if (annees <= 6) return 2.0;
        return 2.5;
    }
}

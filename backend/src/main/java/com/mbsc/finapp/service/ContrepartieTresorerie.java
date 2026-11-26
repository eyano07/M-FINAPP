package com.mbsc.finapp.service;

import java.util.List;

/**
 * Comptes admis en contrepartie d'une sortie de trésorerie (note de frais de décaissement, 26/11/2026) : ceux que l'on
 * débite quand l'argent sort de la caisse, de la banque ou du mobile money (SYSCOHADA révisé).
 *
 * <ul>
 *   <li>6 : charges 60 à 67 (pas les dotations 68-69, qui ne sortent pas d'argent) ;</li>
 *   <li>2 : immobilisations 21 à 27, prêts et dépôts compris (pas les amortissements 28 ni les dépréciations 29) ;</li>
 *   <li>4 : tiers 40 à 48 (fournisseurs, personnel, organismes sociaux, État, associés, divers ; pas les dépréciations 49) ;</li>
 *   <li>16-17 : remboursement d'emprunts et de dettes de location-acquisition ;</li>
 *   <li>83, 87, 89 : charges HAO, participation des travailleurs, impôt sur le résultat.</li>
 * </ul>
 * Pendant côté écran : {@code utils/contrepartieTresorerie.ts}.
 */
public final class ContrepartieTresorerie {

    private static final List<String> PREFIXES_DECAISSEMENT = List.of(
        "60", "61", "62", "63", "64", "65", "66", "67",
        "21", "22", "23", "24", "25", "26", "27",
        "40", "41", "42", "43", "44", "45", "46", "47", "48",
        "16", "17",
        "83", "87", "89");

    private ContrepartieTresorerie() { }

    /** Le compte peut-il être débité en contrepartie d'un décaissement ? */
    public static boolean decaissementAdmis(String numero) {
        if (numero == null || numero.length() < 2) {
            return false;
        }
        return PREFIXES_DECAISSEMENT.contains(numero.substring(0, 2));
    }

    static String messageRefus(String numero) {
        return "Le compte " + numero + " ne peut pas être la contrepartie d'un décaissement : choisissez une charge (60 à 67), "
            + "une immobilisation (21 à 27), un compte de tiers (40 à 48), un emprunt (16-17) ou une charge HAO (83, 87, 89).";
    }
}

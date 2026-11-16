package com.mbsc.finapp.domain.enums;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Duree couverte par un budget : l'exercice entier, un semestre, un trimestre ou un seul mois. Associee a un
 * numero de periode (1 a 2 pour un semestre, 1 a 4 pour un trimestre, 1 a 12 pour un mois, toujours 1 pour
 * l'annee), elle fixe les mois de l'exercice que le budget couvre (voir V122).
 *
 * <p>Exemple : un logiciel installe en novembre recoit un budget MENSUEL de la periode 12 (decembre), au lieu
 * d'un budget annuel pour une annee qui s'acheve.</p>
 */
public enum DureeBudget {
    ANNUEL(12),
    SEMESTRIEL(6),
    TRIMESTRIEL(3),
    MENSUEL(1);

    private final int nombreMois;

    DureeBudget(int nombreMois) {
        this.nombreMois = nombreMois;
    }

    public int nombreMois() {
        return nombreMois;
    }

    /** Nombre de periodes de cette duree dans un exercice : 1, 2, 4 ou 12. */
    public int nombrePeriodes() {
        return 12 / nombreMois;
    }

    public boolean periodeValide(int periode) {
        return periode >= 1 && periode <= nombrePeriodes();
    }

    /** Premier mois couvert (1 = janvier de l'exercice). */
    public int moisDebut(int periode) {
        exigerPeriode(periode);
        return (periode - 1) * nombreMois + 1;
    }

    /** Dernier mois couvert (12 = decembre de l'exercice). */
    public int moisFin(int periode) {
        exigerPeriode(periode);
        return periode * nombreMois;
    }

    /** « Exercice 2026 », « 2e semestre 2026 », « 4e trimestre 2026 », « Décembre 2026 ». */
    public String libelle(int periode, int exercice) {
        exigerPeriode(periode);
        return switch (this) {
            case ANNUEL -> "Exercice " + exercice;
            case SEMESTRIEL -> ordinal(periode) + " semestre " + exercice;
            case TRIMESTRIEL -> ordinal(periode) + " trimestre " + exercice;
            case MENSUEL -> {
                String mois = Month.of(periode).getDisplayName(TextStyle.FULL, Locale.FRANCE);
                yield Character.toUpperCase(mois.charAt(0)) + mois.substring(1) + " " + exercice;
            }
        };
    }

    /** « Annuel », « Semestriel », « Trimestriel », « Mensuel ». */
    public String nom() {
        return switch (this) {
            case ANNUEL -> "Annuel";
            case SEMESTRIEL -> "Semestriel";
            case TRIMESTRIEL -> "Trimestriel";
            case MENSUEL -> "Mensuel";
        };
    }

    private static String ordinal(int n) {
        return n == 1 ? "1er" : n + "e";
    }

    private void exigerPeriode(int periode) {
        if (!periodeValide(periode)) {
            throw new IllegalArgumentException("Période " + periode + " invalide pour un budget " + nom().toLowerCase(Locale.FRANCE)
                + " : de 1 à " + nombrePeriodes() + ".");
        }
    }
}

package com.mbsc.finapp.domain.enums;

/**
 * Statut journalier de présence d'un employé (module DRH_PRESENCES).
 *
 * <p>{@code estPresence()} détermine ce qui compte comme jour presté pour
 * le calcul du pourcentage de présence (utilisable ensuite comme
 * {@code presencePct} d'un bulletin de paie) : seuls PRESENT et MISSION
 * comptent, un jour en mission restant un jour de travail effectif.</p>
 */
public enum StatutPresence {
    PRESENT("P", true, false),
    ABSENT("A", false, true),
    CONGE("C", false, true),
    MALADIE("M", false, true),
    MISSION("J", true, true),
    FERIE("F", false, false);

    private final String code;
    private final boolean presence;
    private final boolean requiertMotif;

    StatutPresence(String code, boolean presence, boolean requiertMotif) {
        this.code = code;
        this.presence = presence;
        this.requiertMotif = requiertMotif;
    }

    public String getCode() {
        return code;
    }

    public boolean estPresence() {
        return presence;
    }

    public boolean requiertMotif() {
        return requiertMotif;
    }
}

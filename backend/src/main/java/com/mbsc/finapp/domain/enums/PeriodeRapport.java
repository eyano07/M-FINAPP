package com.mbsc.finapp.domain.enums;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Période d'un rapport de caisse : le jour, la semaine (du lundi au dimanche) ou le mois qui contient une date de
 * référence.
 */
public enum PeriodeRapport {
    JOUR,
    SEMAINE,
    MOIS;

    /** Premier et dernier jour de la période, inclus. */
    public record Bornes(LocalDate du, LocalDate au) {
    }

    public Bornes bornes(LocalDate date) {
        return switch (this) {
            case JOUR -> new Bornes(date, date);
            case SEMAINE -> {
                LocalDate lundi = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Bornes(lundi, lundi.plusDays(6));
            }
            case MOIS -> new Bornes(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()));
        };
    }
}

package com.mbsc.finapp.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Execution d'un service dans son budget de reference (celui en execution aujourd'hui, a defaut le plus recent ou il
 * a des lignes) : montants de depenses en devise de base, periode couverte et temps ecoule.
 */
public record ExecutionServiceResponse(
    Long serviceId,
    Long budgetId,
    String budgetReference,
    String budgetIntitule,
    String statutBudget,
    /** « Annuel », « Semestriel », « Trimestriel », « Mensuel ». */
    String duree,
    /** « Exercice 2026 », « 2e semestre 2026 »… */
    String periode,
    LocalDate dateDebut,
    LocalDate dateFin,
    long joursTotal,
    /** Jours ecoules de la periode (0 avant son debut, joursTotal apres sa fin). */
    long joursEcoules,
    BigDecimal prevu,
    BigDecimal realise,
    BigDecimal engage,
    BigDecimal disponible,
    /** Realise / prevu, en % (null si rien n'est prevu). */
    BigDecimal tauxExecution,
    BigDecimal depassement,
    String devise
) {
}

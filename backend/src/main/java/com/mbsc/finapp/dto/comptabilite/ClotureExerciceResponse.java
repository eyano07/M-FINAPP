package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Résultat d'une clôture annuelle formelle (audit du 18/08/2026, A-02). */
public record ClotureExerciceResponse(
    LocalDate dateCloture,
    BigDecimal resultatNet,
    String pieceClotureReference,
    String pieceReevaluationReference,
    int reevaluationsReversees,
    int creancesReevaluees
) {}

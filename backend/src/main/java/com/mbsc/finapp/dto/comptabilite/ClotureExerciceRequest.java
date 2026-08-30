package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Demande de clôture annuelle formelle (audit du 18/08/2026, A-02). */
public record ClotureExerciceRequest(@NotNull LocalDate dateCloture) {
}

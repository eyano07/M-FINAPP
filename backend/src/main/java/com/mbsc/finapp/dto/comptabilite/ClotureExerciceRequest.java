package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Demande de clôture définitive de l'exercice (DFIN), approuvée ensuite par le DA ou le DG. */
public record ClotureExerciceRequest(@NotNull LocalDate dateCloture, @Size(max = 1000) String motif) {
}

package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record SortieTravailleurRequest(
    @NotNull Long employeId,
    @NotNull LocalDate dateSortie,
    @NotNull LocalTime heureSortie,
    LocalTime heureRetour,
    @NotBlank @Size(max = 300) String motif,
    @Size(max = 500) String notes
) {}

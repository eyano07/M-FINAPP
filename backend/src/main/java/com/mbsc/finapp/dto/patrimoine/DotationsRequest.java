package com.mbsc.finapp.dto.patrimoine;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Comptabilisation des dotations echues jusqu'a la date incluse. */
public record DotationsRequest(@NotNull LocalDate jusqua) {}

package com.mbsc.finapp.dto.rapprochement;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Pointage manuel : lignes du relevé et écritures du compte de même total. */
public record PointageRequest(@NotEmpty List<Long> ligneIds, @NotEmpty List<Long> ecritureIds) {}

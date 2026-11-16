package com.mbsc.finapp.dto.transport;

import com.mbsc.finapp.domain.enums.StatutTrajet;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record TrajetRequest(
    @NotNull Long vehiculeId,
    Long conducteurId,
    Instant dateDepart,
    Instant dateArrivee,
    @Size(max = 255) String origine,
    @Size(max = 255) String destination,
    /** Raison du déplacement ; facultatif (V144). */
    @Size(max = 255) String motif,
    @PositiveOrZero BigDecimal distanceKm,
    StatutTrajet statut
) {}

package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.enums.StatutPresence;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PresenceRequest(
    @NotNull Long employeId,
    @NotNull LocalDate date,
    @NotNull StatutPresence statut,
    @Size(max = 200) String motif
) {}

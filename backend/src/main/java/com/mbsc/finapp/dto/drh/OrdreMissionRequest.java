package com.mbsc.finapp.dto.drh;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OrdreMissionRequest(
    @NotBlank @Size(max = 200) String lieuMission,
    @PositiveOrZero BigDecimal distanceVille,
    @Size(max = 80) String province,
    @Size(max = 120) String territoire,
    /** Requis si {@code agents} en compte plus d'un — voir {@code OrdreMissionService.valider}. */
    Long superviseurEmployeId,
    @Size(max = 10) String superviseurCivilite,
    @NotBlank @Size(max = 1000) String butMission,
    @Size(max = 100) String dureeMission,
    @NotNull LocalDate dateDepart,
    @NotNull LocalDate dateRetour,
    @Size(max = 150) String moyenTransport,
    @Size(max = 200) String fraisMission,
    @NotEmpty @Valid List<AgentOrdreMissionInput> agents
) {}

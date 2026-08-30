package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentOrdreMissionInput(
    @NotNull Long employeId,
    @Size(max = 150) String fonctionMission,
    @Size(max = 10) String civilite
) {}

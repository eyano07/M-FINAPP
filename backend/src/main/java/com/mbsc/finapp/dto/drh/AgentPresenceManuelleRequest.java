package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentPresenceManuelleRequest(
    @NotBlank @Size(max = 160) String nomComplet,
    @Size(max = 120) String fonction
) {}

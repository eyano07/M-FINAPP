package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SiteOperationnelRequest(
    @NotBlank @Size(max = 120) String nom,
    @Size(max = 200) String localisation,
    boolean actif
) {}

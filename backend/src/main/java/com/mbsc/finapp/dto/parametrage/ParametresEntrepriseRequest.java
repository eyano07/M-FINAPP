package com.mbsc.finapp.dto.parametrage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ParametresEntrepriseRequest(
    @NotBlank @Size(max = 100) String nom,
    @Size(max = 255) String nomComplet,
    @Size(max = 255) String slogan
) {}

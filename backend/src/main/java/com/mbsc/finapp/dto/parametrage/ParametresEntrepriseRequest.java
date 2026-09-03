package com.mbsc.finapp.dto.parametrage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ParametresEntrepriseRequest(
    @NotBlank @Size(max = 100) String nom,
    @Size(max = 255) String nomComplet,
    @Size(max = 255) String slogan,
    @Size(max = 255) String adresse,
    @Size(max = 30) String telephone,
    /** Un ou plusieurs contacts, texte libre (ex. "contact@x.com · direction@x.com") — voir le papier a en-tete. */
    @Size(max = 255) String email,
    @Size(max = 80) String rccm,
    @Size(max = 80) String idNat,
    @Size(max = 40) String nif
) {}

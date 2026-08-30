package com.mbsc.finapp.dto.vente;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creation ou modification d'une fiche client. */
public record ClientRequest(

    @NotBlank(message = "Le code est obligatoire")
    @Size(max = 40)
    String code,

    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 200)
    String nom,

    @Size(max = 40)
    String telephone,

    @Email(message = "Adresse e-mail invalide")
    @Size(max = 150)
    String email,

    @Size(max = 255)
    String adresse,

    Boolean actif
) {}

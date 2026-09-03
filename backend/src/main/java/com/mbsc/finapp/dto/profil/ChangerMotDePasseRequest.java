package com.mbsc.finapp.dto.profil;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps de PUT /profil/mot-de-passe. Meme contrainte de taille que UserCreateRequest. */
public record ChangerMotDePasseRequest(
    @NotBlank String ancienMotDePasse,
    @NotBlank @Size(min = 8, max = 100) String nouveauMotDePasse
) {}

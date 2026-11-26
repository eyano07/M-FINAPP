package com.mbsc.finapp.dto.profil;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de PUT /profil/preferences : une preference d'affichage a enregistrer. La cle et la valeur sont validees contre la liste
 * blanche de ProfilService ; la cle voyage dans le corps et non dans le chemin (elle contient des points).
 */
public record PreferenceRequest(
    @NotBlank @Size(max = 60) String cle,
    @NotBlank @Size(max = 60) String valeur
) {}

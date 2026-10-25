package com.mbsc.finapp.dto.instance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Serveur local : adresse du serveur en ligne et cle d'appairage generee sur celui-ci. Cle vide = garder la cle deja
 * enregistree (seule l'adresse change).
 */
public record ServeurDistantRequest(
    @NotBlank @Size(max = 300) String adresse,
    @Size(max = 200) String cle
) {}

package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Duplique un camion deja receptionne : meme minerais, meme entrepot, meme
 * prix propose et memes frais ad hoc — seule la plaque change. Raccourci de
 * saisie pour un arrivage de plusieurs camions identiques du meme projet.
 */
public record DupliquerCamionRequest(
    @NotBlank @Size(max = 40) String plaque
) {}

package com.mbsc.finapp.dto.rapprochement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Écriture créée à partir d'une ligne du relevé absente de la comptabilité (frais, agios, intérêts...). */
public record RegularisationRequest(@NotBlank String compteContrepartie, @Size(max = 255) String libelle) {}

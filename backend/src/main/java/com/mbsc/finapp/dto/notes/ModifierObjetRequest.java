package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Correction du libellé (objet) d'une note de frais soumise, par le DFIN
 * lors de sa vérification.
 */
public record ModifierObjetRequest(

    @NotBlank(message = "Le libellé est obligatoire")
    @Size(max = 200, message = "Le libellé ne peut pas dépasser 200 caractères")
    String objet
) {}

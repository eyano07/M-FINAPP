package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Création d'un sous-compte analytique. Numéro final = parentNumero + suffixe
 * (format SYSCOHADA, ex. 94 + 11 = 9411).
 */
public record SousCompteAnalytiqueRequest(
    @NotBlank @Pattern(regexp = "9[2-9]\\d*", message = "Le compte parent doit appartenir aux comptes analytiques 92 à 99.")
    String parentNumero,
    @NotBlank @Pattern(regexp = "\\d{1,6}", message = "Le suffixe ne doit contenir que des chiffres (1 à 6).")
    String suffixe,
    @NotBlank @Size(max = 200) String libelle
) {}

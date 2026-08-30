package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la requête POST /comptes — ajout manuel d'un compte OHADA.
 * Le numéro final = parentNumero + "." + suffixe
 * Le type et la classe sont hérités du compte parent.
 */
public record CompteRequest(
    @NotBlank @Size(max = 20) String parentNumero,
    @NotBlank @Size(max = 10) String suffixe,
    @NotBlank @Size(max = 200) String libelle
) {}

package com.mbsc.finapp.dto.profil;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps de PUT /profil. L'email n'est pas modifiable ici : c'est
 * l'identifiant de connexion, son changement releve de l'administration
 * (AdminController), pas de l'auto-service.
 */
public record ProfilUpdateRequest(
    @NotBlank @Size(max = 100) String nom,
    @NotBlank @Size(max = 100) String prenom,
    // Vide/absent autorise (retire le numero) : chiffres, espaces et + - ( ) uniquement.
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "Numero de telephone invalide")
    String telephone
) {}

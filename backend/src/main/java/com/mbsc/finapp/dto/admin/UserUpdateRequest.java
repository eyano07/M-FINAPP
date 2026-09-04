package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Corps de la requête PUT /admin/users/{id} — modification d'un utilisateur.
 * motDePasse est optionnel : null = inchangé.
 */
public record UserUpdateRequest(
    @NotBlank @Size(max = 100) String nom,
    @NotBlank @Size(max = 100) String prenom,
    @Size(min = 8, max = 100) String motDePasse,
    // Vide/absent autorise : chiffres, espaces et + - ( ) uniquement (meme regle que ProfilUpdateRequest).
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "Numero de telephone invalide")
    String telephone,
    @Size(max = 100) String fonction,
    @Size(max = 100) String affectation,
    @NotEmpty List<String> roles
) {}

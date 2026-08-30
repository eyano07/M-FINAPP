package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
    @NotEmpty List<String> roles
) {}

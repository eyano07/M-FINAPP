package com.mbsc.finapp.dto.auth;

import com.mbsc.finapp.security.IdentifiantConnexion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps de POST /auth/login. {@code email} est l'identifiant de connexion : adresse e-mail OU nom
 * d'utilisateur (le champ garde son nom pour les clients existants) — voir {@link IdentifiantConnexion}.
 */
public record LoginRequest(
    @NotBlank @Size(max = 190)
    @Pattern(regexp = IdentifiantConnexion.MOTIF_SAISIE, message = IdentifiantConnexion.MESSAGE_SAISIE)
    String email,
    @NotBlank @Size(max = 128) String motDePasse
) {}

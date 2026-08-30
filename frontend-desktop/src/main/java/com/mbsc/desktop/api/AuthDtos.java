package com.mbsc.desktop.api;

import java.util.Set;

/** DTOs d'authentification (miroir du backend). */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(String email, String motDePasse) {}

    public record UserInfo(
        Long id,
        String email,
        String nom,
        String prenom,
        Set<String> roles
    ) {}

    public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserInfo user
    ) {}
}

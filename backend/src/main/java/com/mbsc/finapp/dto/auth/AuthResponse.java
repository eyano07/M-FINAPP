package com.mbsc.finapp.dto.auth;

import java.util.Set;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserInfo user
) {
    public record UserInfo(
        Long id,
        String email,
        String nom,
        String prenom,
        String telephone,
        String photoUrl,
        Set<String> roles
    ) {}
}

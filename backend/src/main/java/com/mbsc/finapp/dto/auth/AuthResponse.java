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
        Set<String> roles,
        /** Vrai tant que le mot de passe provisoire choisi par l'administrateur n'a pas ete remplace (V131) : l'ecran
         *  bloque alors l'utilisateur sur la page de changement de mot de passe. */
        boolean doitChangerMotDePasse,
        /** Vrai si la double authentification est obligatoire pour ce compte et pas encore activée (C-23) : l'écran
         *  bloque alors l'utilisateur sur la page d'activation. */
        boolean doitActiverDoubleAuth
    ) {}
}

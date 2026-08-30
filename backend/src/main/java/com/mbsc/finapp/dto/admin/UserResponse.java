package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;

import java.util.List;

/**
 * Vue d'un utilisateur pour l'administration (sans donnee sensible).
 */
public record UserResponse(
    Long id,
    String nom,
    String prenom,
    String email,
    boolean actif,
    List<String> roles
) {
    public static UserResponse from(User u) {
        List<String> roles = u.getRoles().stream()
            .map(Role::getNom)
            .map(Enum::name)
            .sorted()
            .toList();
        return new UserResponse(
            u.getId(),
            u.getNom(),
            u.getPrenom(),
            u.getEmail(),
            u.isActif(),
            roles
        );
    }
}

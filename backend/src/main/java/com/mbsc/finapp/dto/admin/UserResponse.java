package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;

import java.util.List;

/**
 * Vue d'un utilisateur pour l'administration (sans donnee sensible).
 */
public record UserResponse(
    Long id,
    String nom,
    String prenom,
    String email,
    String telephone,
    String fonction,
    String affectation,
    boolean actif,
    List<String> roles,
    /** Roles dont les droits d'origine viennent d'etre poses a cette occasion (vide ailleurs). */
    List<String> droitsParDefaut
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
            u.getTelephone(),
            u.getFonction(),
            u.getAffectation(),
            u.isActif(),
            roles,
            List.of()
        );
    }

    /** Meme vue, signalant les roles qui n'avaient aucun droit et ont recu ceux d'origine. */
    public UserResponse avecDroitsParDefaut(List<RoleType> rolesAvecDroitsPoses) {
        return new UserResponse(id, nom, prenom, email, telephone, fonction, affectation, actif, roles,
            rolesAvecDroitsPoses.stream().map(Enum::name).toList());
    }
}

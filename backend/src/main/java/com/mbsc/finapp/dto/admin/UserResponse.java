package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;

import java.util.List;
import java.util.function.Predicate;

/**
 * Vue d'un utilisateur pour l'administration (sans donnee sensible).
 */
public record UserResponse(
    Long id,
    String nom,
    String prenom,
    String email,
    String telephone,
    String emailNotification,
    String fonction,
    String affectation,
    boolean actif,
    /** Vrai tant que l'utilisateur n'a pas remplace le mot de passe choisi par l'administrateur (V131). */
    boolean motDePasseProvisoire,
    List<String> roles,
    /** Roles dont les droits d'origine viennent d'etre poses a cette occasion (vide ailleurs). */
    List<String> droitsParDefaut
) {
    public static UserResponse from(User u) {
        return from(u, role -> true);
    }

    /**
     * Meme vue, sans les roles que {@code roleVisible} refuse : l'administration ne montre pas les roles dont le
     * module est desactive. Ils restent portes par le compte (reactiver le module les rend), simplement absents de l'ecran.
     */
    public static UserResponse from(User u, Predicate<RoleType> roleVisible) {
        List<String> roles = u.getRoles().stream()
            .map(Role::getNom)
            .filter(roleVisible)
            .map(Enum::name)
            .sorted()
            .toList();
        return new UserResponse(
            u.getId(),
            u.getNom(),
            u.getPrenom(),
            u.getEmail(),
            u.getTelephone(),
            u.getEmailNotification(),
            u.getFonction(),
            u.getAffectation(),
            u.isActif(),
            u.isDoitChangerMotDePasse(),
            roles,
            List.of()
        );
    }

    /** Meme vue, signalant les roles qui n'avaient aucun droit et ont recu ceux d'origine. */
    public UserResponse avecDroitsParDefaut(List<RoleType> rolesAvecDroitsPoses) {
        return new UserResponse(id, nom, prenom, email, telephone, emailNotification, fonction, affectation, actif,
            motDePasseProvisoire, roles,
            rolesAvecDroitsPoses.stream().map(Enum::name).toList());
    }
}

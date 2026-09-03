package com.mbsc.finapp.dto.profil;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;

import java.util.List;

/**
 * Profil de l'utilisateur courant. {@code photoUrl} suit la meme convention
 * que {@code ParametresEntrepriseResponse.logoUrl} : chemin relatif a la
 * racine de l'API (le frontend y prefixe apiBase), null si aucune photo.
 */
public record ProfilResponse(
    Long id,
    String email,
    String nom,
    String prenom,
    String telephone,
    String photoUrl,
    List<String> roles
) {
    public static ProfilResponse from(User u) {
        List<String> roles = u.getRoles().stream()
            .map(Role::getNom)
            .map(Enum::name)
            .sorted()
            .toList();
        return new ProfilResponse(
            u.getId(),
            u.getEmail(),
            u.getNom(),
            u.getPrenom(),
            u.getTelephone(),
            u.getPhotoCheminStockage() != null ? "/profil/photo" : null,
            roles
        );
    }
}

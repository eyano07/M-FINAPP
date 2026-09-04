package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Corps de la requête POST /admin/users — création d'un utilisateur.
 *
 * <p>telephone/fonction/affectation sont optionnels (tous les comptes n'en
 * ont pas besoin, ex. ADMIN) ; renseignés, ils alimentent le papier à
 * en-tête individuel de cet utilisateur — voir PapierEnteteService. Permet
 * à l'ADMIN de préremplir le téléphone d'un nouvel employé qui ne s'est pas
 * encore connecté pour le renseigner lui-même (voir aussi /profil, la même
 * donnée en auto-service).</p>
 */
public record UserCreateRequest(
    @NotBlank @Size(max = 100) String nom,
    @NotBlank @Size(max = 100) String prenom,
    @NotBlank @Email @Size(max = 150) String email,
    @NotBlank @Size(min = 8, max = 100) String motDePasse,
    // Vide/absent autorise : chiffres, espaces et + - ( ) uniquement (meme regle que ProfilUpdateRequest).
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "Numero de telephone invalide")
    String telephone,
    @Size(max = 100) String fonction,
    @Size(max = 100) String affectation,
    @NotEmpty List<String> roles
) {}

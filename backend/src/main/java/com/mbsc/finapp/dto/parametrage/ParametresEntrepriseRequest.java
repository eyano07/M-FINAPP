package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.enums.ModeleEntete;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ParametresEntrepriseRequest(
    @NotBlank @Size(max = 100) String nom,
    @Size(max = 255) String nomComplet,
    @Size(max = 255) String slogan,
    @Size(max = 255) String adresse,
    @Size(max = 30) String telephone,
    /** Un ou plusieurs contacts, texte libre (ex. "contact@x.com · direction@x.com") — voir le papier a en-tete. */
    @Size(max = 255) String email,
    @Size(max = 80) String rccm,
    @Size(max = 80) String idNat,
    @Size(max = 40) String nif,
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "La couleur doit etre un code hexadecimal (ex. #15803D)")
    String couleurPrimaire,
    /** Facultatif : absent, le modèle actuel est conservé. */
    ModeleEntete modeleEntete,
    /** Adresse du serveur pour les applications Android (URL ou IP) ; vide : aucune. */
    @Size(max = 255)
    @Pattern(regexp = "^$|^(?i)(https?://)?[a-z0-9.-]+(:\\d{1,5})?(/.*)?$",
        message = "Adresse du serveur invalide (ex. 192.168.1.10, http://192.168.1.10:8080 ou https://erp.exemple.com)")
    String adresseServeur
) {}

package com.mbsc.finapp.dto.fournisseur;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Création ou modification d'une fiche fournisseur ; seul le nom est obligatoire, le compte est ouvert par l'application. */
public record FournisseurRequest(

    @NotBlank(message = "Le nom ou la raison sociale est obligatoire")
    @Size(max = 200)
    String nom,

    @Size(max = 150)
    String contact,

    @Size(max = 40)
    String telephone,

    @Email(message = "Adresse e-mail invalide")
    @Size(max = 150)
    String email,

    @Size(max = 255)
    String adresse,

    @Size(max = 40)
    String nif,

    @Size(max = 60)
    String rccm
) {}

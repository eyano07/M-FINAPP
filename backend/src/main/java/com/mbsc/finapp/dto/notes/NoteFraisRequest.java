package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Donnees de creation / mise a jour d'une note de frais (etat BROUILLON).
 * Une note comporte une ou plusieurs lignes de depense ; le montant total
 * est calcule comme la somme de ces lignes.
 */
public record NoteFraisRequest(

    @NotBlank
    @Size(max = 200)
    String objet,

    @NotBlank
    @Size(max = 200)
    String beneficiaire,

    @Size(max = 2000)
    String description,

    /** Devise commune a toutes les lignes de la note (CDF par defaut si absente). */
    Devise devise,

    /**
     * DECAISSEMENT (defaut si absent) ou ENCAISSEMENT. Une note d'encaissement
     * ne peut etre creee que par un CAISSIER ou un ADMIN (verifie en service).
     */
    SensTransaction sens,

    @NotEmpty(message = "La note doit comporter au moins une ligne de depense")
    @Valid
    List<LigneNoteFraisRequest> lignes
) {}

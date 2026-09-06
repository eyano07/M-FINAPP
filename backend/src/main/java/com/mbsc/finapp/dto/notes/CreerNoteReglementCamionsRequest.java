package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Demande de reglement, via note de frais (circuit DFIN/DA/Tresorerie), de
 * la dette fournisseur d'un ou plusieurs camions de minerais deja valides
 * par la caisse. Une ligne est generee par camion, imputee au compte
 * Fournisseurs (4011) pour son montant TTC — voir {@code
 * NoteFraisService.creerReglementCamionsMinerai}.
 */
public record CreerNoteReglementCamionsRequest(

    @NotEmpty(message = "Selectionnez au moins un camion a regler")
    List<Long> camionIds,

    /** Destinataire reel du paiement (le fournisseur du minerais). */
    @NotBlank
    @Size(max = 200)
    String beneficiaire,

    @Size(max = 2000)
    String description
) {}

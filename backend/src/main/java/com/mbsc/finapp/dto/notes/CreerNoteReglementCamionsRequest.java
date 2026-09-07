package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Demande de reglement, via note de frais (circuit DFIN/DA/Tresorerie), de
 * la dette fournisseur d'un ou plusieurs camions de minerais et/ou de leurs
 * frais accessoires deja postes (transport, peage, pont bascule...). Une
 * ligne est generee par camion et par frais, toutes imputees au compte
 * Fournisseurs (4011) — voir {@code
 * NoteFraisService.creerReglementCamionsMinerai}. Au moins l'une des deux
 * listes doit etre non vide (verifie en service : la regle porte sur les
 * deux ensemble, pas sur chacune separement).
 */
public record CreerNoteReglementCamionsRequest(

    List<Long> camionIds,

    /** Frais accessoires deja postes (piece non nulle) a regler avec les camions ci-dessus. */
    List<Long> chargeIds,

    /** Destinataire reel du paiement (le fournisseur ou le prestataire). */
    @NotBlank
    @Size(max = 200)
    String beneficiaire,

    @Size(max = 2000)
    String description
) {}

package com.mbsc.finapp.dto.fournisseur;

import com.mbsc.finapp.domain.Fournisseur;

/**
 * Fiche fournisseur ; {@code nombreAchats} compte les lots de stock et les immobilisations qui portent son nom : une
 * fiche qui en a s'archive, une fiche qui n'en a aucun peut se supprimer.
 */
public record FournisseurResponse(
    Long id,
    String code,
    String nom,
    String contact,
    String telephone,
    String email,
    String adresse,
    String nif,
    String rccm,
    String compteComptable,
    boolean actif,
    long nombreAchats
) {
    public static FournisseurResponse from(Fournisseur f, long nombreAchats) {
        return new FournisseurResponse(
            f.getId(), f.getCode(), f.getNom(), f.getContact(), f.getTelephone(), f.getEmail(), f.getAdresse(),
            f.getNif(), f.getRccm(), f.getCompteComptable(), f.isActif(), nombreAchats);
    }
}

package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @param delta          variation signee appliquee au stock de vides (+ ou -)
 * @param venteReference vente a l'origine du mouvement, pour les mouvements automatiques
 */
public record MouvementEmballageResponse(
    Long id,
    Long emballageId,
    String emballageCode,
    String emballageLibelle,
    TypeMouvementEmballage type,
    Integer quantite,
    Integer delta,
    LocalDate dateMouvement,
    String motif,
    Long venteId,
    String venteReference,
    String createdByNom,
    Instant createdAt
) {
    public static MouvementEmballageResponse from(MouvementEmballage m) {
        var e = m.getEmballage();
        var vente = m.getVente();
        var auteur = m.getCreatedBy();
        String nom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        return new MouvementEmballageResponse(
            m.getId(),
            e.getId(),
            e.getCode(),
            e.getLibelle(),
            m.getType(),
            m.getQuantiteBouteilles(),
            m.getType().delta(m.getQuantiteBouteilles()),
            m.getDateMouvement(),
            m.getMotif(),
            vente == null ? null : vente.getId(),
            vente == null ? null : vente.getReference(),
            nom,
            m.getCreatedAt()
        );
    }
}

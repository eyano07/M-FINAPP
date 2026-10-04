package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @param delta          variation signee appliquee au stock de vides (+ ou -)
 * @param venteReference vente a l'origine du mouvement, pour les mouvements automatiques
 * @param annule         true si ce mouvement a ete annule
 * @param annulationDeId mouvement que celui-ci annule, pour le mouvement inverse d'une annulation
 * @param annulable      true si ce mouvement peut etre annule depuis l'ecran des mouvements —
 *                       un ajustement d'inventaire exige en plus le role administrateur
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
    Instant createdAt,
    boolean annule,
    Long annulationDeId,
    boolean annulable
) {
    public static MouvementEmballageResponse from(MouvementEmballage m) {
        var e = m.getEmballage();
        var vente = m.getVente();
        var auteur = m.getCreatedBy();
        String nom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        var annulationDe = m.getAnnulationDe();
        // Une perte de boisson saisie avant d'etre reliee a sa sortie de
        // stock ne peut pas retablir ce stock : elle n'est pas proposee.
        boolean annulable = !m.isAnnule() && annulationDe == null
            && m.getType().estSaisieManuelle()
            && (!m.getType().sortDuStockDeBoisson() || m.getMouvementStock() != null);
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
            m.getCreatedAt(),
            m.isAnnule(),
            annulationDe == null ? null : annulationDe.getId(),
            annulable
        );
    }
}

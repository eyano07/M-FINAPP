package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vue resumee d'une note de frais (listes, tableaux de bord).
 */
public record NoteFraisResponse(
    Long id,
    String reference,
    String objet,
    String beneficiaire,
    BigDecimal montant,
    Devise devise,
    StatutNote statut,
    SensTransaction sens,
    PrioriteNote priorite,
    String createurNom,
    int nombreLignes,
    int nombrePiecesJointes,
    Instant dateCreation,
    Instant dateMaj
) {
    public static NoteFraisResponse from(NoteFrais n) {
        var createur = n.getCreateur();
        return new NoteFraisResponse(
            n.getId(),
            n.getReference(),
            n.getObjet(),
            n.getBeneficiaire(),
            n.getMontant(),
            n.getDevise(),
            n.getStatut(),
            n.getSens(),
            n.getPriorite(),
            createur == null ? null : nomComplet(createur.getPrenom(), createur.getNom()),
            n.getLignes().size(),
            n.getPiecesJointes().size(),
            n.getDateCreation(),
            n.getDateMaj()
        );
    }

    private static String nomComplet(String prenom, String nom) {
        return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
    }
}

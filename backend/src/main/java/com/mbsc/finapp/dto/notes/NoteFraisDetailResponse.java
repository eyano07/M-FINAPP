package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Vue detaillee d'une note de frais : entete + lignes de depense + pieces
 * jointes + chronologie complete du workflow.
 */
public record NoteFraisDetailResponse(
    Long id,
    String reference,
    String objet,
    String beneficiaire,
    String description,
    BigDecimal montant,
    Devise devise,
    StatutNote statut,
    SensTransaction sens,
    PrioriteNote priorite,
    String demandeurNom,
    String demandeurEmail,
    List<LigneNoteFraisResponse> lignes,
    List<PieceJointeResponse> piecesJointes,
    Instant dateCreation,
    Instant dateMaj,
    List<ObservationResponse> observations
) {
    public static NoteFraisDetailResponse from(NoteFrais n) {
        var createur = n.getCreateur();
        String createurNom = createur == null ? null
            : ((createur.getPrenom() == null ? "" : createur.getPrenom()) + " "
             + (createur.getNom() == null ? "" : createur.getNom())).trim();

        List<ObservationResponse> timeline = n.getObservations().stream()
            .map(ObservationResponse::from)
            .toList();
        List<LigneNoteFraisResponse> lignes = n.getLignes().stream()
            .map(LigneNoteFraisResponse::from)
            .toList();
        List<PieceJointeResponse> pieces = n.getPiecesJointes().stream()
            .map(PieceJointeResponse::from)
            .toList();

        return new NoteFraisDetailResponse(
            n.getId(),
            n.getReference(),
            n.getObjet(),
            n.getBeneficiaire(),
            n.getDescription(),
            n.getMontant(),
            n.getDevise(),
            n.getStatut(),
            n.getSens(),
            n.getPriorite(),
            createurNom,
            createur == null ? null : createur.getEmail(),
            lignes,
            pieces,
            n.getDateCreation(),
            n.getDateMaj(),
            timeline
        );
    }
}

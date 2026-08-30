package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.enums.StatutBudget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Vue detaillee d'un budget : entete, totaux et lignes.
 */
public record BudgetResponse(
    Long id,
    String intitule,
    Integer exercice,
    StatutBudget statut,
    String elaboreParNom,
    String approuveParNom,
    String observation,
    BigDecimal totalPrevu,
    BigDecimal totalRealise,
    Instant dateCreation,
    List<LigneBudgetResponse> lignes
) {
    public static BudgetResponse from(Budget b) {
        List<LigneBudgetResponse> lignes = b.getLignes().stream()
            .map(LigneBudgetResponse::from)
            .toList();

        BigDecimal totalPrevu = lignes.stream()
            .map(LigneBudgetResponse::montantPrevu)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRealise = lignes.stream()
            .map(LigneBudgetResponse::montantRealise)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BudgetResponse(
            b.getId(),
            b.getIntitule(),
            b.getExercice(),
            b.getStatut(),
            nom(b.getElaborePar()),
            nom(b.getApprouvePar()),
            b.getObservation(),
            totalPrevu,
            totalRealise,
            b.getDateCreation(),
            lignes
        );
    }

    private static String nom(com.mbsc.finapp.domain.User u) {
        if (u == null) {
            return null;
        }
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " "
              + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}

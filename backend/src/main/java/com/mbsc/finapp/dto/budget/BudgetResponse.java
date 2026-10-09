package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutBudget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Vue detaillee d'un budget : en-tete (reference, version, circuit d'approbation), totaux prevus par section
 * et lignes ventilees par mois. Le realise se consulte avec le suivi (GET /budgets/{id}/suivi).
 */
public record BudgetResponse(
    Long id,
    String reference,
    String intitule,
    Integer exercice,
    StatutBudget statut,
    int numeroRevision,
    Long revisionDeId,
    String revisionDeReference,
    String elaboreParNom,
    String approuveParNom,
    String observation,
    String motifRejet,
    Instant dateCreation,
    Instant dateSoumission,
    Instant dateApprobation,
    Instant dateExecution,
    Instant dateCloture,
    BigDecimal totalProduits,
    BigDecimal totalCharges,
    BigDecimal totalInvestissements,
    /** Produits - charges prevus. */
    BigDecimal resultatPrevisionnel,
    List<LigneBudgetResponse> lignes
) {
    public static BudgetResponse from(Budget b) {
        List<LigneBudgetResponse> lignes = b.getLignes().stream()
            .map(LigneBudgetResponse::from)
            .sorted(Comparator.comparing(LigneBudgetResponse::compteNumero, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        BigDecimal produits = totalSection(lignes, "PRODUITS");
        BigDecimal charges = totalSection(lignes, "CHARGES");
        Budget parent = b.getRevisionDe();
        return new BudgetResponse(
            b.getId(), b.getReference(), b.getIntitule(), b.getExercice(), b.getStatut(),
            b.getNumeroRevision(),
            parent == null ? null : parent.getId(),
            parent == null ? null : parent.getReference(),
            nom(b.getElaborePar()), nom(b.getApprouvePar()),
            b.getObservation(), b.getMotifRejet(),
            b.getDateCreation(), b.getDateSoumission(), b.getDateApprobation(), b.getDateExecution(), b.getDateCloture(),
            produits, charges, totalSection(lignes, "INVESTISSEMENTS"), produits.subtract(charges),
            lignes
        );
    }

    static BigDecimal totalSection(List<LigneBudgetResponse> lignes, String section) {
        return lignes.stream()
            .filter(l -> section.equals(l.section()))
            .map(LigneBudgetResponse::montantPrevu)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String nom(User u) {
        if (u == null) {
            return null;
        }
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}

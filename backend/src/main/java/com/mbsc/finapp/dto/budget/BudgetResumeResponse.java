package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Un budget dans la liste : en-tete, totaux prevus et realises (grand livre) par section, et taux d'execution
 * des depenses. {@code totalPrevu} / {@code totalRealise} portent sur les depenses (charges + investissements),
 * seule mesure qui ait un sens pour un « taux d'execution » (le tableau de bord les utilise).
 */
public record BudgetResumeResponse(
    Long id,
    String reference,
    String intitule,
    Integer exercice,
    StatutBudget statut,
    int numeroRevision,
    String elaboreParNom,
    String approuveParNom,
    Instant dateCreation,
    BigDecimal prevuProduits,
    BigDecimal prevuCharges,
    BigDecimal prevuInvestissements,
    BigDecimal realiseProduits,
    BigDecimal realiseCharges,
    BigDecimal realiseInvestissements,
    BigDecimal totalPrevu,
    BigDecimal totalRealise,
    /** Depenses realisees / prevues, en % (null si rien n'est prevu). */
    BigDecimal tauxExecution,
    int nombreLignes,
    DureeBudget duree,
    int periode,
    String libellePeriode,
    int moisDebut,
    int moisFin,
    /** Services ayant des lignes dans le budget. */
    int nombreServices,
    /**
     * Depassement du budget consolide sur la periode : somme, sur les lignes de depenses, de (realise + engage -
     * prevu) quand c'est positif. Zero pour un budget jamais mis en execution.
     */
    BigDecimal montantDepassement,
    /** Lignes du budget consolide en depassement. */
    int nombreDepassements,
    /** Services dont l'enveloppe est depassee ou qui ont des depenses non budgetees (budget mis en execution). */
    List<String> servicesEnDepassement
) {}

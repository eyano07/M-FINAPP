package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.enums.StatutBudget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Suivi d'execution d'un budget, en devise de base (USD) : pour chaque ligne et chaque mois, le prevu, le
 * realise (grand livre) et l'engage (notes approuvees non payees) ; totaux par section et par nature SYSCOHADA ;
 * resultat previsionnel ; depenses passees sur des comptes non budgetes ; notes de frais hors budget.
 *
 * <p>Ecart = realise - prevu, pour toutes les lignes : positif sur une charge = depassement (defavorable),
 * positif sur un produit = recette superieure a la prevision (favorable).</p>
 */
public record SuiviBudgetResponse(
    Long budgetId,
    String reference,
    String intitule,
    Integer exercice,
    StatutBudget statut,
    int numeroRevision,
    LocalDate calculeLe,
    /** Mois de l'exercice entierement ou partiellement ecoules (0 avant l'exercice, 12 apres). */
    int moisEcoules,
    String devise,
    List<LigneSuivi> lignes,
    List<TotalSuivi> sections,
    List<TotalSuivi> natures,
    TotalSuivi resultat,
    List<CompteHorsBudget> horsBudget,
    List<NoteHorsBudget> notesHorsBudget,
    List<String> avertissements
) {
    public record LigneSuivi(
        Long id,
        String compteNumero,
        String compteLibelle,
        String section,
        String nature,
        String commentaire,
        List<BigDecimal> prevu,
        List<BigDecimal> realise,
        List<BigDecimal> engage,
        BigDecimal prevuAnnuel,
        BigDecimal realiseAnnuel,
        BigDecimal engageAnnuel,
        /** Prevu annuel - realise - engage. */
        BigDecimal disponibleAnnuel,
        BigDecimal ecartAnnuel,
        BigDecimal tauxExecution,
        /** Cumuls de janvier au dernier mois ecoule. */
        BigDecimal prevuADate,
        BigDecimal realiseADate,
        BigDecimal ecartADate
    ) {}

    /** Total d'une section (PRODUITS, CHARGES, INVESTISSEMENTS), d'une nature (60, 61...) ou du resultat. */
    public record TotalSuivi(
        String code,
        String libelle,
        String section,
        List<BigDecimal> prevu,
        List<BigDecimal> realise,
        List<BigDecimal> engage,
        BigDecimal prevuAnnuel,
        BigDecimal realiseAnnuel,
        BigDecimal engageAnnuel,
        BigDecimal tauxExecution
    ) {}

    /** Compte budgetable mouvemente pendant l'exercice mais couvert par aucune ligne du budget. */
    public record CompteHorsBudget(
        String compteNumero,
        String compteLibelle,
        String section,
        List<BigDecimal> realise,
        BigDecimal realiseAnnuel
    ) {}

    /** Note de frais de l'exercice dont le controle a la soumission exigeait une justification. */
    public record NoteHorsBudget(
        Long id,
        String reference,
        String objet,
        BigDecimal montant,
        String devise,
        String statut,
        String statutBudget,
        String justification,
        String createurNom,
        Instant dateCreation
    ) {}
}

package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Suivi d'execution d'un budget sur sa periode, en devise de base (USD) : pour chaque ligne et chaque mois, le
 * prevu, le realise (grand livre) et l'engage (notes approuvees non payees) ; totaux par section et par nature
 * SYSCOHADA ; resultat previsionnel ; depenses passees sur des comptes non budgetes ; notes de frais hors budget ;
 * execution par service et depassements.
 *
 * <p>{@code lignes}, {@code sections}, {@code natures} et {@code resultat} decrivent le budget consolide (un
 * compte par ligne, tous services confondus) : il recoit toutes les ecritures de la periode. {@code services}
 * detaille l'enveloppe de chaque service (voir ImputationBudgetaire).</p>
 *
 * <p>Ecart = realise - prevu, pour toutes les lignes : positif sur une charge = depassement (defavorable),
 * positif sur un produit = recette superieure a la prevision (favorable). Les montants « annuels » portent sur
 * la periode du budget (l'annee, le semestre, le trimestre ou le mois).</p>
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
    List<String> avertissements,
    DureeBudget duree,
    int periode,
    String libellePeriode,
    int moisDebut,
    int moisFin,
    /** true si au moins une ligne est rattachee a un service. */
    boolean parService,
    /** Execution de l'enveloppe de chaque service (et des lignes generales d'un budget par service). */
    List<SuiviService> services,
    /**
     * Depenses sans service passees sur des comptes que seuls des services ont budgetes : elles comptent dans le
     * budget consolide mais dans l'enveloppe d'aucun service.
     */
    List<CompteHorsBudget> nonRattache,
    /** Depassements de la periode : enveloppes des services puis budget consolide, les plus importants d'abord. */
    List<Depassement> depassements
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
        /** Prevu de la periode - realise - engage. */
        BigDecimal disponibleAnnuel,
        BigDecimal ecartAnnuel,
        BigDecimal tauxExecution,
        /** Cumuls de janvier au dernier mois ecoule. */
        BigDecimal prevuADate,
        BigDecimal realiseADate,
        BigDecimal ecartADate,
        /** Services dont les lignes forment cette ligne consolidee (vide : lignes generales seulement). */
        List<String> services,
        /** Realise + engage - prevu quand c'est positif sur une depense (charge, investissement), zero sinon. */
        BigDecimal depassement
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

    /** Compte budgetable mouvemente pendant la periode mais couvert par aucune ligne (du budget, ou du service). */
    public record CompteHorsBudget(
        String compteNumero,
        String compteLibelle,
        String section,
        List<BigDecimal> realise,
        BigDecimal realiseAnnuel,
        /** Notes approuvees non payees sur ce compte, pendant la periode. */
        BigDecimal engageAnnuel
    ) {}

    /** Note de frais de la periode dont le controle a la soumission exigeait une justification. */
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
        Instant dateCreation,
        String serviceNom
    ) {}

    /**
     * Enveloppe d'un service sur la periode : ses lignes (prevu, realise, engage, disponible), ses depenses qu'aucune
     * ligne ne couvre (« hors budget du service ») et ses notes de frais. {@code serviceId} null : lignes generales.
     */
    public record SuiviService(
        Long serviceId,
        String nom,
        String responsable,
        boolean actif,
        List<LigneSuivi> lignes,
        /** Depenses prevues (charges + investissements) sur les lignes du service. */
        BigDecimal prevu,
        /** Realise impute sur ses lignes de depenses. */
        BigDecimal realise,
        /** Engage impute sur ses lignes de depenses. */
        BigDecimal engage,
        /** Prevu - realise - engage sur ses lignes de depenses. */
        BigDecimal disponible,
        /** Realise / prevu, en % (null si rien n'est prevu). */
        BigDecimal tauxExecution,
        /** Depenses et engagements du service qu'aucune ligne (du service ni generale) ne couvre. */
        BigDecimal nonBudgete,
        /** Depassements de ses lignes, plus ses depenses non budgetees. */
        BigDecimal depassement,
        int nombreDepassements,
        /** Depenses du service imputees sur les lignes generales (enveloppe commune), faute de ligne du service. */
        BigDecimal imputeGeneral,
        /** Depenses du service qu'aucune ligne (du service ni generale) ne couvre, par compte. */
        List<CompteHorsBudget> horsBudget,
        List<NoteService> notes
    ) {}

    /** Note de frais d'un service sur la periode (hors brouillons et notes annulees). */
    public record NoteService(
        Long id,
        String reference,
        String objet,
        BigDecimal montant,
        String devise,
        String statut,
        String statutBudget,
        Instant dateCreation
    ) {}

    /**
     * Un depassement de la periode. {@code niveau} : SERVICE (enveloppe d'un service, ou lignes generales d'un budget
     * par service) ou CONSOLIDE (budget unique). {@code nature} : DEPASSEMENT (realise + engage au-dela du prevu
     * d'une ligne) ou NON_BUDGETE (depense d'un service sur un compte qu'aucune de ses lignes ne couvre).
     */
    public record Depassement(
        String niveau,
        String nature,
        Long serviceId,
        String serviceNom,
        String responsable,
        String compteNumero,
        String compteLibelle,
        String section,
        BigDecimal prevu,
        BigDecimal realise,
        BigDecimal engage,
        BigDecimal montant
    ) {}
}

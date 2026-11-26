package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.enums.StatutControleBudget;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultat du controle budgetaire d'une note (ou d'une note en cours de saisie) : statut de chaque ligne
 * et statut global (le plus defavorable). Montants budgetaires en devise de base (USD) ; disponible =
 * prevu cumule a fin de mois - realise cumule - engage.
 */
public record ControleBudgetaireResponse(
    Integer exercice,
    int mois,
    Long budgetId,
    String budgetReference,
    String budgetIntitule,
    StatutControleBudget statut,
    boolean justificationRequise,
    String devise,
    BigDecimal tauxChange,
    List<LigneControle> lignes,
    List<String> avertissements,
    /** false : module Budget désactivé par l'administrateur, aucune dépense n'est contrôlée ni rattachée. */
    boolean budgetActif,
    /** Période du budget de référence (« Décembre 2026 », « Exercice 2026 »...), null sans budget. */
    String budgetPeriode,
    /** Service de la dépense (null : aucun). */
    Long serviceId,
    String serviceNom,
    String serviceResponsable
) {
    /** Réponse sans service ni période (module désactivé, note d'encaissement...). */
    public ControleBudgetaireResponse(Integer exercice, int mois, Long budgetId, String budgetReference, String budgetIntitule,
                                      StatutControleBudget statut, boolean justificationRequise, String devise, BigDecimal tauxChange,
                                      List<LigneControle> lignes, List<String> avertissements, boolean budgetActif) {
        this(exercice, mois, budgetId, budgetReference, budgetIntitule, statut, justificationRequise, devise, tauxChange, lignes,
            avertissements, budgetActif, null, null, null, null);
    }

    public record LigneControle(
        int index,
        String compteNumero,
        String compteLibelle,
        /** Montant HT de la ligne dans la devise de la note. */
        BigDecimal montant,
        /** Meme montant en devise de base (null si le taux de change manque). */
        BigDecimal montantBase,
        StatutControleBudget statut,
        String ligneBudgetCompte,
        String ligneBudgetLibelle,
        BigDecimal prevuCumule,
        BigDecimal realiseCumule,
        BigDecimal engage,
        BigDecimal disponibleCumule,
        /** Prévu sur toute la période du budget (l'année, le semestre, le trimestre ou le mois). */
        BigDecimal prevuAnnuel,
        /** Disponible sur toute la période du budget. */
        BigDecimal disponibleAnnuel,
        String message,
        /** Enveloppe contrôlée : « service Restaurant », « lignes générales », « budget consolidé » (null : budget sans service). */
        String enveloppe
    ) {}
}

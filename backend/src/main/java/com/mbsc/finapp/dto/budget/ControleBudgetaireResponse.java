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
    boolean budgetActif
) {
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
        BigDecimal prevuAnnuel,
        BigDecimal disponibleAnnuel,
        String message
    ) {}
}

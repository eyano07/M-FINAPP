package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une ligne budgetaire : un compte du plan comptable (classes 2, 6, 7, 8) et ses douze montants mensuels.
 * Les trimestres, semestres et l'annee en sont deduits. A defaut de {@code mensuel}, {@code montantPrevu}
 * (annuel) est reparti a parts egales.
 */
public record LigneBudgetRequest(
    @NotBlank
    String compteNumero,

    /** Douze montants, janvier a decembre de l'exercice, positifs ou nuls. */
    @Size(min = 12, max = 12, message = "La ventilation mensuelle doit compter douze mois")
    List<@DecimalMin(value = "0.00", message = "Un montant mensuel ne peut etre negatif") BigDecimal> mensuel,

    /** Montant annuel, utilise seulement si {@code mensuel} est absent (repartition a parts egales). */
    @DecimalMin(value = "0.00", message = "Le montant prevu ne peut etre negatif")
    BigDecimal montantPrevu,

    /** Hypothese ou base de calcul de la ligne. */
    @Size(max = 500)
    String commentaire
) {}

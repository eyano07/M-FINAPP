package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une ligne budgetaire : un compte du plan comptable (classes 2, 6, 7, 8), ses douze montants mensuels et,
 * pour une depense, le service dont elle forme l'enveloppe. Les trimestres, semestres et l'annee en sont
 * deduits. A defaut de {@code mensuel}, {@code montantPrevu} (montant de la periode) est reparti a parts egales
 * sur les mois de la periode du budget.
 */
public record LigneBudgetRequest(
    @NotBlank
    String compteNumero,

    /** Douze montants, janvier a decembre de l'exercice, positifs ou nuls ; zero hors de la periode du budget. */
    @Size(min = 12, max = 12, message = "La ventilation mensuelle doit compter douze mois")
    List<@DecimalMin(value = "0.00", message = "Un montant mensuel ne peut etre negatif") BigDecimal> mensuel,

    /** Montant de la periode, utilise seulement si {@code mensuel} est absent (repartition a parts egales). */
    @DecimalMin(value = "0.00", message = "Le montant prevu ne peut etre negatif")
    BigDecimal montantPrevu,

    /** Hypothese ou base de calcul de la ligne. */
    @Size(max = 500)
    String commentaire,

    /** Service de la ligne (null : ligne generale). Seules les depenses se rattachent a un service. */
    Long serviceId
) {}

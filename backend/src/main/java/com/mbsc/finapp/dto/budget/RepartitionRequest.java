package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Ventilation d'un montant sur les mois d'une periode (l'exercice entier par defaut) : UNIFORME (parts egales)
 * ou SAISONNALITE (au prorata du realise mensuel de l'annee precedente sur le compte, sur les memes mois ; parts
 * egales a defaut d'historique).
 */
public record RepartitionRequest(
    /** Montant de la periode (le nom est historique : il valait toujours l'annee). */
    @NotNull @DecimalMin("0.00") BigDecimal montantAnnuel,
    @NotBlank String mode,
    String compteNumero,
    Integer exercice,
    /** Premier mois de la periode (1 si absent). */
    @Min(1) @Max(12) Integer moisDebut,
    /** Dernier mois de la periode (12 si absent). */
    @Min(1) @Max(12) Integer moisFin
) {}

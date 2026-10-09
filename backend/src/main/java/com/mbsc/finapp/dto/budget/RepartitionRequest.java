package com.mbsc.finapp.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Ventilation d'un montant annuel sur douze mois : UNIFORME (parts egales) ou SAISONNALITE (au prorata du
 * realise mensuel de l'annee precedente sur le compte, parts egales a defaut d'historique).
 */
public record RepartitionRequest(
    @NotNull @DecimalMin("0.00") BigDecimal montantAnnuel,
    @NotBlank String mode,
    String compteNumero,
    Integer exercice
) {}

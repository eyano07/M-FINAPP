package com.mbsc.finapp.dto.provision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reprise (totale ou partielle) d'une provision.
 *
 * @param compteRepriseNumero compte de reprise (79x en exploitation, 77x en
 *                            financier/HAO selon la nature de l'évènement)
 */
public record RepriseRequest(
    @NotNull @Positive BigDecimal montant,
    @NotBlank String compteRepriseNumero,
    String motif,
    @NotNull LocalDate dateReprise
) {}

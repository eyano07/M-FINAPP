package com.mbsc.finapp.dto.provision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Constitution d'une provision.
 *
 * @param compteProvisionNumero compte de provision (15x/19x pour un risque,
 *                              29x/39x/49x/59x pour une dépréciation)
 * @param compteDotationNumero  compte de dotation (68x/69x)
 */
public record ProvisionRequest(
    @NotBlank String libelle,
    @NotBlank String compteProvisionNumero,
    @NotBlank String compteDotationNumero,
    @NotNull @Positive BigDecimal montant,
    @NotNull LocalDate dateConstitution
) {}

package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Frais accessoire a incorporer au cout d'acquisition d'un camion de minerais.
 *
 * @param compteChargeNumero compte de charge par nature (611 transports sur
 *                           achats, 6288 services exterieurs divers...). Choisi
 *                           a chaque fois : le peage, le pont bascule et le
 *                           transport ne relevent pas du meme compte.
 */
public record ChargeCamionRequest(
    @NotBlank @Size(max = 200) String libelle,
    @NotBlank String compteChargeNumero,
    @NotNull @Positive BigDecimal montant,
    @NotNull LocalDate dateCharge
) {}

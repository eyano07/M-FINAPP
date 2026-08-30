package com.mbsc.finapp.dto.caisse;

import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Saisie d'une operation de caisse directe (hors note de frais).
 *
 * @param compteContrepartie numero du compte OHADA de charge (decaissement)
 *                           ou de produit (encaissement) ; la caisse (571)
 *                           est l'autre membre de l'ecriture en partie double.
 */
public record TransactionCaisseRequest(

    @NotNull
    @DecimalMin(value = "0.01", message = "Le montant doit etre strictement positif")
    BigDecimal montant,

    @NotNull
    SensTransaction sens,

    @NotBlank
    String compteContrepartie,

    @Size(max = 255)
    String libelle
) {}

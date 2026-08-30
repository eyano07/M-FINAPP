package com.mbsc.finapp.dto.mobilemoney;

import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Saisie d'une operation mobile money directe (hors note de frais).
 *
 * @param compteContrepartie numero du compte OHADA de charge (decaissement)
 *                           ou de produit (encaissement) ; le compte de
 *                           l'operateur choisi est l'autre membre de l'ecriture.
 * @param etablissementId    operateur mobile money utilise — obligatoire :
 *                           chaque operateur a son propre compte et donc son
 *                           propre solde ; son nom est ajoute au libelle.
 */
public record TransactionMobileMoneyRequest(

    @NotNull
    @DecimalMin(value = "0.01", message = "Le montant doit etre strictement positif")
    BigDecimal montant,

    @NotNull
    SensTransaction sens,

    @NotBlank
    String compteContrepartie,

    @Size(max = 255)
    String libelle,

    @NotNull(message = "L'operateur mobile money est obligatoire")
    Long etablissementId
) {}

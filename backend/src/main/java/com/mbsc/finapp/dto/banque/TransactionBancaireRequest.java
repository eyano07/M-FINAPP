package com.mbsc.finapp.dto.banque;

import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Saisie d'une operation bancaire directe (hors note de frais).
 *
 * @param compteContrepartie numero du compte OHADA de charge (decaissement)
 *                           ou de produit (encaissement) ; le compte de la
 *                           banque choisie est l'autre membre de l'ecriture.
 * @param etablissementId    banque utilisee — obligatoire : chaque banque a
 *                           son propre compte et donc son propre solde.
 */
public record TransactionBancaireRequest(

    @NotNull
    @DecimalMin(value = "0.01", message = "Le montant doit etre strictement positif")
    BigDecimal montant,

    @NotNull
    SensTransaction sens,

    @NotBlank
    String compteContrepartie,

    @Size(max = 255)
    String libelle,

    @NotNull(message = "La banque est obligatoire")
    Long etablissementId
) {}

package com.mbsc.finapp.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Transaction de caisse poussee par le poste caissier.
 * L'{@code uuid} est la cle d'idempotence : un meme uuid renvoye plusieurs fois
 * ne cree qu'une seule transaction cote serveur.
 */
public record TransactionSyncDto(

    @NotBlank
    String uuid,

    String reference,

    /** Reference de la note de frais associee, si paiement de note. */
    String noteReference,

    @NotNull
    @Positive
    BigDecimal montant,

    @NotBlank
    String sens,

    String numeroRecu,

    String caissierEmail,

    String dateOperation,

    List<EcritureSyncDto> ecritures
) {}

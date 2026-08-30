package com.mbsc.finapp.dto.banque;

import jakarta.validation.constraints.NotNull;

/** Banque a preciser pour payer une note de frais par virement. */
public record PayerNoteBancaireRequest(
    @NotNull(message = "La banque est obligatoire")
    Long etablissementId
) {}

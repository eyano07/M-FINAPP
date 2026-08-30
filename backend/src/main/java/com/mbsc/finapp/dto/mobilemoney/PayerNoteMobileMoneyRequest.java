package com.mbsc.finapp.dto.mobilemoney;

import jakarta.validation.constraints.NotNull;

/** Operateur mobile money a preciser pour payer une note de frais. */
public record PayerNoteMobileMoneyRequest(
    @NotNull(message = "L'operateur mobile money est obligatoire")
    Long etablissementId
) {}

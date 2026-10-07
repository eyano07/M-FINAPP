package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotNull;

/** Régime de TVA de l'entreprise : assujettie ou non (voir TauxTvaService). */
public record AssujettissementTvaRequest(
    @NotNull(message = "Indiquez si l'entreprise est assujettie à la TVA") Boolean assujetti
) {}

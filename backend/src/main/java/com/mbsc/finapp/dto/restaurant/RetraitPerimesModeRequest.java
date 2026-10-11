package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import jakarta.validation.constraints.NotNull;

/** Choix de l'administrateur : retrait des plats périmés manuel (avec notification) ou automatique. */
public record RetraitPerimesModeRequest(
    @NotNull ModeRetraitPerimes mode
) {}

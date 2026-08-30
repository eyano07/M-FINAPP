package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.enums.ModeReglement;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Encaissement d'une creance client issue d'une vente CREDIT (audit du
 * 18/08/2026, A-04). {@code modeReglement} est le canal d'encaissement
 * reel (CAISSE, BANQUE ou MOBILE_MONEY — jamais CREDIT lui-meme).
 */
public record ReglerCreanceRequest(
    @NotNull ModeReglement modeReglement,
    Long etablissementId,
    @NotNull LocalDate dateReglement
) {}

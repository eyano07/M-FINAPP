package com.mbsc.finapp.dto.patrimoine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultat d'une campagne de comptabilisation des dotations.
 *
 * @param ignorees biens ecartes et raison (periode close, compte manquant...)
 */
public record DotationsResponse(
    int lignesComptabilisees,
    BigDecimal totalDotations,
    List<String> piecesCreees,
    List<String> ignorees
) {}

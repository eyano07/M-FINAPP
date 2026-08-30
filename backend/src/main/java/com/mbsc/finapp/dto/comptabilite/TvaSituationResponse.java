package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Situation TVA d'une période : collectée (ventes, 443x), récupérable
 * (achats, 445x) et solde net.
 *
 * @param soldeNet positif = TVA due à l'État (à provisionner en 4441),
 *                 négatif = crédit de TVA à reporter (4449)
 */
public record TvaSituationResponse(
    LocalDate du,
    LocalDate au,
    BigDecimal tvaCollectee,
    BigDecimal tvaRecuperable,
    BigDecimal soldeNet,
    List<LigneTvaResponse> lignes
) {}

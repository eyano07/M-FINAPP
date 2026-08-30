package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Bilan à une date donnée : actif d'un côté, passif (y compris le résultat
 * net de la période, intégré aux capitaux propres) de l'autre.
 */
public record BilanResponse(
    LocalDate au,
    List<EtatFinancierLigne> actifs,
    List<EtatFinancierLigne> passifs,
    BigDecimal totalActif,
    BigDecimal totalPassifHorsResultat,
    BigDecimal resultatNet,
    BigDecimal totalPassif,
    boolean equilibre
) {
}

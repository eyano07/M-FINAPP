package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Compte de résultat sur une période : produits (classe 7) moins
 * charges (classe 6), en devise de base (CDF).
 */
public record CompteResultatResponse(
    LocalDate du,
    LocalDate au,
    List<EtatFinancierLigne> produits,
    List<EtatFinancierLigne> charges,
    BigDecimal totalProduits,
    BigDecimal totalCharges,
    BigDecimal resultatNet
) {
}

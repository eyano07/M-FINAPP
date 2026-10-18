package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** État de la comptabilité analytique de gestion (SYSCOHADA classe 9, 92 à 99) sur une période. */
public record EtatAnalytiqueResponse(
    LocalDate du,
    LocalDate au,
    List<LigneAnalytiqueResponse> lignes,
    BigDecimal totalMouvementDebit,
    BigDecimal totalMouvementCredit,
    BigDecimal totalSoldeDebiteur,
    BigDecimal totalSoldeCrediteur,
    boolean equilibre
) {}

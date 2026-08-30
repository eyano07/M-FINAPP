package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceVerificationResponse(
    LocalDate du,
    LocalDate au,
    List<LigneBalanceVerificationResponse> lignes,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal totalSoldeDebiteur,
    BigDecimal totalSoldeCrediteur,
    boolean equilibree
) {}

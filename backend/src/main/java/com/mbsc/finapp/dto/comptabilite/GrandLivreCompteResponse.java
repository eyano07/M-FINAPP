package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GrandLivreCompteResponse(
    String compteNumero,
    String compteLibelle,
    LocalDate du,
    LocalDate au,
    BigDecimal reportDebit,
    BigDecimal reportCredit,
    BigDecimal reportSolde,
    List<LigneGrandLivreResponse> lignes
) {}

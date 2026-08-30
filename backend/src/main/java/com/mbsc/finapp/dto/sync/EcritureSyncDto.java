package com.mbsc.finapp.dto.sync;

import java.math.BigDecimal;

/**
 * Une ecriture comptable telle que calculee par le poste caissier hors-ligne.
 * Le compte est designe par son numero OHADA.
 */
public record EcritureSyncDto(
    String numeroCompte,
    String libelle,
    BigDecimal debit,
    BigDecimal credit,
    String dateEcriture
) {}

package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Livre-journal : toutes les pièces comptabilisées d'une période, en ordre
 * chronologique, avec leurs lignes (obligation légale OHADA).
 */
public record LivreJournalResponse(
    LocalDate du,
    LocalDate au,
    List<PieceResponse> pieces,
    BigDecimal totalDebit,
    BigDecimal totalCredit
) {
}

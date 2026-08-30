package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.TauxChange;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Réponse GET /admin/taux-change : taux courant + historique.
 */
public record TauxChangeResponse(
    BigDecimal taux,
    LocalDate dateEffet,
    java.util.List<HistoriqueEntry> historique
) {
    public record HistoriqueEntry(
        Long id,
        BigDecimal taux,
        LocalDate dateEffet,
        String note,
        String source,
        Instant createdAt
    ) {
        public static HistoriqueEntry from(TauxChange t) {
            return new HistoriqueEntry(t.getId(), t.getTaux(), t.getDateEffet(), t.getNote(), t.getSource(), t.getCreatedAt());
        }
    }
}

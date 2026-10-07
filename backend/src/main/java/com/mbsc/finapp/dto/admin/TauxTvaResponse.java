package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.TauxTva;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reponse GET /admin/taux-tva : taux en vigueur aujourd'hui + historique.
 */
public record TauxTvaResponse(
    /** Taux effectivement appliqué aux opérations : 0 si l'entreprise n'est pas assujettie. */
    BigDecimal taux,
    LocalDate dateEffet,
    List<HistoriqueEntry> historique,
    /** Régime de TVA de l'entreprise. */
    boolean assujetti,
    /** Taux légal en vigueur (historique), appliqué seulement si l'entreprise est assujettie. */
    BigDecimal tauxEnVigueur
) {
    public record HistoriqueEntry(
        Long id,
        BigDecimal taux,
        LocalDate dateEffet,
        String note,
        String createdByEmail,
        Instant createdAt
    ) {
        public static HistoriqueEntry from(TauxTva t) {
            return new HistoriqueEntry(
                t.getId(),
                t.getTaux(),
                t.getDateEffet(),
                t.getNote(),
                t.getCreatedBy() == null ? null : t.getCreatedBy().getEmail(),
                t.getCreatedAt());
        }
    }
}

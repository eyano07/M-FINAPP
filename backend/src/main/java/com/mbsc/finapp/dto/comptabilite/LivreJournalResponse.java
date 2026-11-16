package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.enums.Devise;

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
    BigDecimal totalCredit,
    /** Devise des montants : USD (devise de base du grand livre), ou CDF quand le journal est affiche en francs. */
    Devise devise,
    /** Francs pour 1 dollar quand les montants sont convertis, {@code null} sinon. */
    BigDecimal taux
) {
    /** Journal dans la devise de base (comptabilite, banque, mobile money, caisse par defaut). */
    public LivreJournalResponse(LocalDate du, LocalDate au, List<PieceResponse> pieces, BigDecimal totalDebit,
                                BigDecimal totalCredit) {
        this(du, au, pieces, totalDebit, totalCredit, Devise.USD, null);
    }
}

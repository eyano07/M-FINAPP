package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PieceResponse(
    Long id,
    String reference,
    LocalDate datePiece,
    JournalComptable journal,
    String libelle,
    StatutPiece statut,
    /** true = reprise des a-nouveaux (alimente les soldes d'ouverture de la balance). */
    boolean soldeOuverture,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    String createdByEmail,
    Instant createdAt,
    Long pieceOrigineId,
    List<LigneEcritureResponse> lignes
) {
    public static PieceResponse from(PieceComptable p) {
        return from(p, true);
    }

    public static PieceResponse from(PieceComptable p, boolean withLignes) {
        var auteur = p.getCreatedBy();
        List<LigneEcritureResponse> lignesDto = withLignes && p.getLignes() != null
            ? p.getLignes().stream().map(LigneEcritureResponse::from).toList()
            : List.of();
        return new PieceResponse(
            p.getId(),
            p.getReference(),
            p.getDatePiece(),
            p.getJournal(),
            p.getLibelle(),
            p.getStatut(),
            p.isSoldeOuverture(),
            p.getTotalDebit(),
            p.getTotalCredit(),
            auteur == null ? null : auteur.getEmail(),
            p.getCreatedAt(),
            p.getPieceOrigine() == null ? null : p.getPieceOrigine().getId(),
            lignesDto
        );
    }
}

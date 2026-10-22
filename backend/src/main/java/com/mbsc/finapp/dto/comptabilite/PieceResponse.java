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
    List<LigneEcritureResponse> lignes,
    /**
     * true si l'écran Comptabilité peut extourner cette pièce : comptabilisée,
     * liée à aucune opération (stock, vente, trésorerie) qui doive l'annuler
     * elle-même, et sans compte de trésorerie — voir ComptabiliteService.annuler.
     * Ces trois indicateurs ne sont calculés que par la liste des pièces ;
     * ils valent false partout ailleurs.
     */
    boolean extournable,
    /** true si ce brouillon peut être comptabilisé depuis l'écran : ni journal ni compte de trésorerie. */
    boolean comptabilisable,
    /** true si la pièce mouvemente au moins un compte de trésorerie (classe 5). */
    boolean tresorerie
) {
    /**
     * Copie de la piece avec ses montants convertis par {@code conversion} (affichage dans une autre devise). Les totaux
     * sont recalcules sur les lignes DEJA converties, pour que les colonnes de la piece s'additionnent exactement ; sans
     * lignes, on convertit les totaux enregistres.
     */
    public PieceResponse enDevise(java.util.function.UnaryOperator<BigDecimal> conversion) {
        List<LigneEcritureResponse> converties = lignes.stream().map(l -> l.enDevise(conversion)).toList();
        BigDecimal debit = converties.isEmpty() ? conversion.apply(totalDebit)
            : converties.stream().map(LigneEcritureResponse::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = converties.isEmpty() ? conversion.apply(totalCredit)
            : converties.stream().map(LigneEcritureResponse::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PieceResponse(id, reference, datePiece, journal, libelle, statut, soldeOuverture, debit, credit,
            createdByEmail, createdAt, pieceOrigineId, converties, extournable, comptabilisable, tresorerie);
    }

    public static PieceResponse from(PieceComptable p) {
        return from(p, true);
    }

    public static PieceResponse from(PieceComptable p, boolean withLignes) {
        return from(p, withLignes, false, false, false);
    }

    public static PieceResponse from(PieceComptable p, boolean withLignes, boolean extournable,
                                     boolean comptabilisable, boolean tresorerie) {
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
            lignesDto,
            extournable,
            comptabilisable,
            tresorerie
        );
    }
}

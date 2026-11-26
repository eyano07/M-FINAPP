package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.ReleveBancaireRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extourne depuis l'ecran Pieces comptables. Depuis la regle du 13/11/2026 (« seul le comptable annule une
 * comptabilisation »), c'est le comptable seul — la regle de role est portee par {@code @PreAuthorize}, relue par
 * {@code SeparationDesTachesTest} — et aucune piece qui mouvemente la tresorerie ne s'extourne d'ici, pas meme
 * celle d'un journal importe sans operation de caisse (le DFIN pouvait la contre-passer la veille) : une correction de
 * tresorerie passe par une note de frais. Une piece adossee a une vraie operation de caisse reste refusee a tous.
 */
class ComptabiliteServiceExtourneTresorerieTest {

    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ComptabiliteService service = new ComptabiliteService(pieces, ecritures,
        mock(CompteOHADARepository.class), mock(MouvementStockRepository.class), mock(VenteRepository.class),
        mock(ReferenceGenerator.class), utilisateur, mock(PeriodeComptableService.class),
        new ConversionDeviseService(mock(com.mbsc.finapp.repository.TauxChangeRepository.class)),
        mock(ReleveBancaireRepository.class), mock(JournalEvenementsComptables.class), notifications);

    private PieceComptable piece;

    @BeforeEach
    void preparer() {
        piece = PieceComptable.builder().id(7L).reference("PC-2026-002124").libelle("Reglement en double")
            .datePiece(LocalDate.of(2026, 11, 5)).statut(StatutPiece.COMPTABILISEE)
            .totalDebit(new BigDecimal("1000")).totalCredit(new BigDecimal("1000")).build();
        piece.addLigne(ligne("6011", "1000", "0"));
        piece.addLigne(ligne("5711", "0", "1000"));
        when(pieces.findWithLignesById(7L)).thenReturn(Optional.of(piece));
        when(utilisateur.requireUser()).thenReturn(mock(User.class));
    }

    private static EcritureGrandLivre ligne(String compte, String debit, String credit) {
        return EcritureGrandLivre.builder().compte(CompteOHADA.builder().numero(compte).libelle(compte).build())
            .debit(new BigDecimal(debit)).credit(new BigDecimal(credit)).build();
    }

    @Test
    void une_piece_de_tresorerie_importee_ne_s_extourne_pas_d_ici() {
        assertThatThrownBy(() -> service.annuler(7L)).hasMessageContaining("mouvemente la trésorerie")
            .hasMessageContaining("note de frais");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
        verify(pieces, never()).save(any(PieceComptable.class));
        verify(notifications, never()).notifierPiece(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void une_piece_adossee_a_une_operation_de_caisse_reste_refusee() {
        when(ecritures.existsTresorerieParPieceId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.annuler(7L)).hasMessageContaining("opération de caisse");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
        verify(pieces, never()).save(any(PieceComptable.class));
    }

    @Test
    void une_piece_ordinaire_s_extourne_et_reste_visible_au_statut_annulee() {
        PieceComptable ordinaire = PieceComptable.builder().id(8L).reference("PC-2026-002125").libelle("Loyer")
            .datePiece(LocalDate.of(2026, 11, 5)).statut(StatutPiece.COMPTABILISEE)
            .journal(JournalComptable.OPERATIONS_DIVERSES)
            .totalDebit(new BigDecimal("900")).totalCredit(new BigDecimal("900")).build();
        ordinaire.addLigne(ligne("6222", "900", "0"));
        ordinaire.addLigne(ligne("4011", "0", "900"));
        when(pieces.findWithLignesById(8L)).thenReturn(Optional.of(ordinaire));

        service.annuler(8L);

        assertThat(ordinaire.getStatut()).isEqualTo(StatutPiece.ANNULEE);
        verify(pieces).save(any(PieceComptable.class));
        // Le DFIN est prevenu de l'annulation faite par le comptable, et invite a la retablir si elle n'est pas justifiee.
        verify(notifications).notifierPiece(eq(List.of(RoleType.DFIN)), isNull(), any(), eq(TypeNotification.PIECE_ANNULEE),
            eq("Pièce annulée"), contains("a annulé la pièce PC-2026-002125 du 05/11/2026 : Loyer (900,00 USD)"), eq(8L));
    }

    /**
     * Annuler la reprise des a-nouveaux la retire des soldes d'ouverture : son extourne porte le meme marqueur, au lieu
     * de laisser l'ouverture intacte et une annulation dans les mouvements de l'exercice.
     */
    @Test
    void l_extourne_d_une_reprise_des_a_nouveaux_reste_un_solde_d_ouverture() {
        PieceComptable reprise = pieceComptabilisee(31L, "2441", "4011");
        reprise.setSoldeOuverture(true);
        when(pieces.findWithLignesById(31L)).thenReturn(Optional.of(reprise));
        org.mockito.ArgumentCaptor<PieceComptable> extourne = org.mockito.ArgumentCaptor.forClass(PieceComptable.class);

        service.annuler(31L);

        verify(pieces).save(extourne.capture());
        assertThat(extourne.getValue().isSoldeOuverture()).isTrue();
        assertThat(extourne.getValue().getPieceOrigine()).isSameAs(reprise);
    }

    /** La liste n'affiche « Annuler » que sur ce que annuler() accepte : plus de piece de tresorerie, meme pour le DFIN. */
    @Test
    void l_ecran_ne_propose_l_extourne_ni_pour_la_tresorerie_ni_pour_les_pieces_liees() {
        PieceComptable ordinaire = pieceComptabilisee(11L, "6222", "4011");
        PieceComptable tresorerie = pieceComptabilisee(12L, "6011", "5711");
        PieceComptable liee = pieceComptabilisee(13L, "6222", "4011");
        when(pieces.findAllWithCreatedByOrderByDatePieceDesc()).thenReturn(List.of(ordinaire, tresorerie, liee));
        when(pieces.idsPiecesTresorerie()).thenReturn(List.of(12L));
        when(pieces.idsPiecesLieesAUneOperation()).thenReturn(List.of(13L));

        var reponse = service.listerPieces();

        assertThat(reponse).extracting(r -> r.id(), r -> r.extournable())
            .containsExactly(tuple(11L, true), tuple(12L, false),
                tuple(13L, false));
    }

    /**
     * « Restaurer » n'est propose que si l'annulation est encore en vigueur : si l'extourne a elle-meme ete annulee
     * (extourne d'extourne, comme PC-2026-000756/802/803), la comptabilisation est deja retablie et une restauration
     * l'ecrirait une seconde fois.
     */
    @Test
    void l_ecran_ne_propose_la_restauration_que_si_l_annulation_est_encore_en_vigueur() {
        PieceComptable annulee = pieceAnnulee(21L);
        PieceComptable dejaRetablie = pieceAnnulee(22L);
        PieceComptable annuleeEtRestauree = pieceAnnulee(23L);
        when(pieces.findAllWithCreatedByOrderByDatePieceDesc()).thenReturn(List.of(annulee, dejaRetablie, annuleeEtRestauree));
        when(pieces.idsPiecesAvecExtourneEnVigueur()).thenReturn(List.of(21L, 23L));
        when(pieces.idsPiecesRestaurees()).thenReturn(List.of(23L));

        var reponse = service.listerPieces();

        assertThat(reponse).extracting(r -> r.id(), r -> r.restaurable())
            .containsExactly(tuple(21L, true), tuple(22L, false),
                tuple(23L, false));
    }

    private static PieceComptable pieceComptabilisee(long id, String debit, String credit) {
        PieceComptable p = PieceComptable.builder().id(id).reference("PC-2026-" + id).libelle("Piece " + id)
            .datePiece(LocalDate.of(2026, 11, 5)).statut(StatutPiece.COMPTABILISEE)
            .journal(JournalComptable.OPERATIONS_DIVERSES)
            .totalDebit(new BigDecimal("100")).totalCredit(new BigDecimal("100")).build();
        p.addLigne(ligne(debit, "100", "0"));
        p.addLigne(ligne(credit, "0", "100"));
        return p;
    }

    private static PieceComptable pieceAnnulee(long id) {
        PieceComptable p = pieceComptabilisee(id, "6222", "4011");
        p.setStatut(StatutPiece.ANNULEE);
        return p;
    }
}

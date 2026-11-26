package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mode de comptabilisation de la paie (audit DRH du 15/11/2026, D-01) : en mode automatique la clôture constate la
 * paie ; en mode manuel elle verrouille seulement, et le DFIN comptabilise ensuite le mois.
 */
class BulletinPaieComptabilisationTest {

    private final BulletinPaieRepository repository = mock(BulletinPaieRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ParametresPaieService parametres = mock(ParametresPaieService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final PaieNoteService paieNotes = mock(PaieNoteService.class);
    private final BulletinPaieService service = new BulletinPaieService(repository, mock(EmployeRepository.class),
        users, parametres, mock(PayrollCalculationService.class), mock(ConversionDeviseService.class), currentUser,
        paieNotes, mock(AvanceSalaireService.class));

    private final User dfin = new User();

    BulletinPaieComptabilisationTest() {
        when(currentUser.requireUser()).thenReturn(dfin);
        // Comme le vrai service : la constatation rattache la pièce aux bulletins.
        doAnswer(i -> {
            List<BulletinPaie> bs = i.getArgument(0);
            PieceComptable piece = PieceComptable.builder().reference("PC-PAIE").build();
            bs.forEach(b -> {
                b.setPieceComptable(piece);
                b.setConstateeALaCloture(true);
            });
            return piece;
        }).when(paieNotes).constaterALaCloture(anyList(), anyInt(), anyInt(), any());
    }

    private void mode(boolean automatique) {
        when(parametres.get()).thenReturn(ParametresPaie.builder().comptabiliserPaie(automatique).build());
    }

    private static BulletinPaie bulletin(boolean cloture) {
        return BulletinPaie.builder()
            .employe(Employe.builder().nomComplet("Agent").matricule("M1").build())
            .mois(9).annee(2026).statut(StatutBulletin.VALIDE)
            .dateCloture(cloture ? Instant.now() : null)
            .salaireNet(new BigDecimal("500"))
            .build();
    }

    @Test
    void modeAutomatiqueLaClotureConstateLaPaie() {
        mode(true);
        List<BulletinPaie> bs = List.of(bulletin(false), bulletin(false));
        when(repository.findByPeriode(9, 2026)).thenReturn(bs);

        service.cloturerPeriode(9, 2026);

        verify(paieNotes).constaterALaCloture(eq(bs), eq(9), eq(2026), eq(dfin));
        assertThat(bs).allMatch(b -> b.getDateCloture() != null && b.getPieceComptable() != null);
    }

    @Test
    void modeManuelLaClotureVerrouilleSansPiecePuisLeDfinComptabilise() {
        mode(false);
        List<BulletinPaie> bs = List.of(bulletin(false), bulletin(false));
        when(repository.findByPeriode(9, 2026)).thenReturn(bs);

        var clotures = service.cloturerPeriode(9, 2026);

        verify(paieNotes, never()).constaterALaCloture(anyList(), anyInt(), anyInt(), any());
        assertThat(bs).allMatch(b -> b.getDateCloture() != null && b.getPieceComptable() == null);
        assertThat(clotures).allMatch(r -> r.cloture() && r.pieceReference() == null);

        var comptabilises = service.comptabiliserPeriode(9, 2026);

        verify(paieNotes).constaterALaCloture(eq(bs), eq(9), eq(2026), eq(dfin));
        assertThat(comptabilises).hasSize(2).allMatch(r -> "PC-PAIE".equals(r.pieceReference()));
    }

    @Test
    void comptabilisationRefuseeSiLeMoisNEstPasCloture() {
        when(repository.findByPeriode(9, 2026)).thenReturn(List.of(bulletin(true), bulletin(false)));

        assertThatThrownBy(() -> service.comptabiliserPeriode(9, 2026))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("n'est pas clôturée");
        verify(paieNotes, never()).constaterALaCloture(anyList(), anyInt(), anyInt(), any());
    }

    @Test
    void comptabilisationRefuseeSiLeMoisEstDejaComptabilise() {
        BulletinPaie b = bulletin(true);
        b.setPieceComptable(PieceComptable.builder().reference("PC-7").build());
        when(repository.findByPeriode(9, 2026)).thenReturn(List.of(b));

        assertThatThrownBy(() -> service.comptabiliserPeriode(9, 2026))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("déjà comptabilisée")
            .hasMessageContaining("PC-7");
        verify(paieNotes, never()).constaterALaCloture(anyList(), anyInt(), anyInt(), any());
    }

    /** Seuls les bulletins sans pièce sont comptabilisés (un mois partiellement comptabilisé). */
    @Test
    void comptabilisationLimiteeAuxBulletinsSansPiece() {
        BulletinPaie deja = bulletin(true);
        deja.setPieceComptable(PieceComptable.builder().reference("PC-7").build());
        BulletinPaie reste = bulletin(true);
        when(repository.findByPeriode(9, 2026)).thenReturn(List.of(deja, reste));

        service.comptabiliserPeriode(9, 2026);

        verify(paieNotes).constaterALaCloture(eq(List.of(reste)), eq(9), eq(2026), eq(dfin));
        assertThat(deja.getPieceComptable().getReference()).isEqualTo("PC-7");
    }
}

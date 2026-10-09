package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieNote;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.OrganismePaie;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Note de paie et notes fiscales : conditions de création et constatation de la paie au paiement. */
class PaieNoteServiceTest {

    private final BulletinPaieRepository bulletins = mock(BulletinPaieRepository.class);
    private final NoteFraisRepository notes = mock(NoteFraisRepository.class);
    private final NoteFraisService noteFraisService = mock(NoteFraisService.class);
    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final PaieNoteService service = new PaieNoteService(
        bulletins, notes, noteFraisService, new PaieComptabilisationService(comptes), comptabilite);

    PaieNoteServiceTest() {
        when(comptes.findByNumero(anyString()))
            .thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(i.getArgument(0)).build()));
    }

    private static BulletinPaie bulletin(StatutBulletin statut, boolean cloture, String net) {
        BigDecimal n = new BigDecimal(net);
        return BulletinPaie.builder()
            .employe(Employe.builder().nomComplet("Agent").matricule("M1").build())
            .mois(9).annee(2026).statut(statut)
            .dateCloture(cloture ? Instant.now() : null)
            .baseImposableInss(n.add(new BigDecimal("60"))).cnssOuvriere(new BigDecimal("30"))
            .cnssPatronale(new BigDecimal("78")).ipr(new BigDecimal("30")).onem(BigDecimal.ONE).inpp(BigDecimal.TEN)
            .salaireNet(n)
            .build();
    }

    private static NoteFrais note(CategorieNote categorie, StatutNote statut, String montant) {
        return NoteFrais.builder().id(7L).reference("NF-2026-000007").categorie(categorie).statut(statut)
            .paieMois(9).paieAnnee(2026).montant(new BigDecimal(montant)).build();
    }

    @Test
    void notePaieRefuseeSiUnBulletinEstEnBrouillon() {
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(
            bulletin(StatutBulletin.VALIDE, true, "500"), bulletin(StatutBulletin.BROUILLON, false, "400")));

        assertThatThrownBy(() -> service.creerNotePaie(9, 2026))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("brouillon");
        verifyNoInteractions(noteFraisService);
    }

    @Test
    void notePaieRefuseeSiLaPaieNestPasCloturee() {
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, false, "500")));

        assertThatThrownBy(() -> service.creerNotePaie(9, 2026))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("Clôturez");
    }

    @Test
    void notePaieRefuseeSiElleExisteDeja() {
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, true, "500")));
        when(notes.findNotesPaie(9, 2026)).thenReturn(List.of(note(CategorieNote.PAIE, StatutNote.SOUMISE, "500")));

        assertThatThrownBy(() -> service.creerNotePaie(9, 2026))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("existe déjà");
    }

    @Test
    void noteFiscaleRefuseeAvantLePaiementDeLaPaie() {
        when(notes.findNotesPaie(9, 2026)).thenReturn(List.of(note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "500")));

        assertThatThrownBy(() -> service.creerNoteImpot(9, 2026, OrganismePaie.IPR))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("payée");
        verifyNoInteractions(noteFraisService);
    }

    @Test
    void noteFiscaleCnssReprendLesPartsSalarialeEtPatronale() {
        assertThat(PaieNoteService.montantOrganisme(
            List.of(bulletin(StatutBulletin.VALIDE, true, "500"), bulletin(StatutBulletin.VALIDE, true, "300")),
            OrganismePaie.CNSS)).isEqualByComparingTo("216");
    }

    @Test
    void paiementDeLaNotePaieEcritLaConstatationEnFinDeMois() {
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "800");
        List<BulletinPaie> lies = List.of(bulletin(StatutBulletin.VALIDE, true, "500"), bulletin(StatutBulletin.VALIDE, true, "300"));
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(lies);
        PieceComptable piece = PieceComptable.builder().reference("PC-1").build();
        when(comptabilite.creerPieceInterne(eq(JournalComptable.OPERATIONS_DIVERSES), anyString(),
            eq(LocalDate.of(2026, 9, 30)), anyList(), any())).thenReturn(piece);

        service.apresPaiementInterne(n, new User());

        assertThat(lies).allMatch(b -> b.getPieceComptable() == piece);
    }

    @Test
    void paiementRefuseSiLeMontantNeCorrespondPlusAuxBulletins() {
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "999");
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, true, "500")));

        assertThatThrownBy(() -> service.apresPaiementInterne(n, new User())).isInstanceOf(IllegalStateException.class);
        verify(comptabilite, never()).creerPieceInterne(any(), any(), any(), any(), any());
    }

    @Test
    void paiementDUneNoteOrdinaireSansEffet() {
        service.apresPaiementInterne(note(CategorieNote.STANDARD, StatutNote.TRANSMISE_CAISSE, "10"), new User());
        verifyNoInteractions(bulletins, comptabilite);
    }
}

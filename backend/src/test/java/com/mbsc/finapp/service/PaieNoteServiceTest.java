package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieNote;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.OrganismePaie;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.notes.LigneNoteFraisRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
    private final PeriodeComptableService periodes = mock(PeriodeComptableService.class);
    private final PaieNoteService service = new PaieNoteService(
        bulletins, notes, noteFraisService, new PaieComptabilisationService(comptes), comptabilite, periodes);

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
        when(comptabilite.creerPieceInterne(eq(JournalComptable.PAIE), anyString(),
            eq(LocalDate.of(2026, 9, 30)), anyList(), any())).thenReturn(piece);

        service.apresPaiementInterne(n, new User());

        assertThat(lies).allMatch(b -> b.getPieceComptable() == piece);
    }

    /** Audit SYSCOHADA du 10/11/2026, C-14 : la paie est constatée à la clôture du mois, au journal de paie. */
    @Test
    void laClotureDuMoisConstateLaPaieAuJournalDePaie() {
        List<BulletinPaie> lies = List.of(bulletin(StatutBulletin.VALIDE, true, "500"), bulletin(StatutBulletin.VALIDE, true, "300"));
        PieceComptable piece = PieceComptable.builder().reference("PC-3").build();
        when(comptabilite.creerPieceInterne(eq(JournalComptable.PAIE), anyString(),
            eq(LocalDate.of(2026, 9, 30)), anyList(), any())).thenReturn(piece);

        service.constaterALaCloture(lies, 9, 2026, new User());

        assertThat(lies).allMatch(b -> b.getPieceComptable() == piece && b.isConstateeALaCloture());
    }

    /** Une paie déjà constatée à la clôture : le paiement de sa note ne passe plus aucune charge. */
    @Test
    void lePaiementDUnePaieConstateeALaClotureNEcritPlusLaConstatation() {
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "500");
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setConstateeALaCloture(true);
        b.setPieceComptable(PieceComptable.builder().reference("PC-4").build());
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(List.of(b));

        service.apresPaiementInterne(n, new User());

        verifyNoInteractions(comptabilite);
    }

    @Test
    void periodeDuMoisDePaieClotureeConstatationAuJourDuPaiement() {
        when(periodes.dateCloture()).thenReturn(LocalDate.of(2026, 9, 30));
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "500");
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, true, "500")));
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any()))
            .thenReturn(PieceComptable.builder().reference("PC-2").build());

        service.apresPaiementInterne(n, new User());

        verify(comptabilite).creerPieceInterne(eq(JournalComptable.PAIE),
            org.mockito.ArgumentMatchers.contains("clôturée"), eq(LocalDate.now()), anyList(), any());
    }

    @Test
    void clotureFutureDateConstatationAuLendemainDeLaCloture() {
        LocalDate cloture = LocalDate.now().plusDays(10);
        when(periodes.dateCloture()).thenReturn(cloture);
        assertThat(service.dateConstatation(9, 2026)).isEqualTo(cloture.plusDays(1));
    }

    @Test
    void periodeOuverteConstatationEnFinDeMois() {
        when(periodes.dateCloture()).thenReturn(LocalDate.of(2026, 8, 31));
        assertThat(service.dateConstatation(9, 2026)).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void paiementRefuseSiLeMontantNeCorrespondPlusAuxBulletins() {
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "999");
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, true, "500")));

        assertThatThrownBy(() -> service.apresPaiementInterne(n, new User())).isInstanceOf(IllegalStateException.class);
        verify(comptabilite, never()).creerPieceInterne(any(), any(), any(), any(), any());
    }

    // --- Audit DRH du 15/11/2026, D-02 : un compte de rémunérations dues par agent ----------------------

    private static BulletinPaie bulletinAgent(String compteAgent, String net) {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, net);
        b.getEmploye().setCompteRemuneration(compteAgent);
        return b;
    }

    /** Pièce de constatation qui crédite les comptes donnés (montant indifférent ici). */
    private static PieceComptable pieceCreditant(String... comptesCredites) {
        PieceComptable piece = PieceComptable.builder().reference("PC-9").build();
        for (String numero : comptesCredites) {
            piece.getLignes().add(EcritureGrandLivre.builder().compte(CompteOHADA.builder().numero(numero).build())
                .debit(BigDecimal.ZERO).credit(BigDecimal.TEN).build());
        }
        return piece;
    }

    @SuppressWarnings("unchecked")
    private List<LigneNoteFraisRequest> lignesDeLaNotePaie(List<BulletinPaie> valides) {
        when(bulletins.findByPeriode(9, 2026)).thenReturn(valides);
        service.creerNotePaie(9, 2026);
        ArgumentCaptor<List<LigneNoteFraisRequest>> lignes = ArgumentCaptor.forClass(List.class);
        verify(noteFraisService).creerEtSoumettreNotePaieInterne(eq(CategorieNote.PAIE), eq(9), eq(2026), isNull(),
            anyString(), anyString(), anyString(), lignes.capture());
        return lignes.getValue();
    }

    @Test
    void notePaieDebiteLeCompteDeChaqueAgent() {
        PieceComptable piece = pieceCreditant("422111", "422112", "4315");
        BulletinPaie a = bulletinAgent("422111", "500");
        BulletinPaie b = bulletinAgent("422112", "300");
        for (BulletinPaie x : List.of(a, b)) {
            x.setPieceComptable(piece);
            x.setConstateeALaCloture(true);
        }

        assertThat(lignesDeLaNotePaie(List.of(a, b))).extracting(LigneNoteFraisRequest::compteImputation)
            .containsExactly("422111", "422112");
    }

    /** Un bulletin constaté sur le collectif avant l'ouverture des comptes par agent reste réglé sur 42211. */
    @Test
    void bulletinConstateSur42211AvantLesComptesParAgentResteRegleSur42211() {
        BulletinPaie b = bulletinAgent("422115", "500");
        b.setPieceComptable(pieceCreditant("42211", "4315"));
        b.setConstateeALaCloture(true);

        assertThat(lignesDeLaNotePaie(List.of(b))).extracting(LigneNoteFraisRequest::compteImputation)
            .containsExactly("42211");
    }

    /** Mode manuel : mois clôturé, pas encore constaté — la note débite le compte de l'agent, à défaut 42211. */
    @Test
    void bulletinNonConstateDebiteLeCompteDeLAgentOuLeCollectif() {
        assertThat(lignesDeLaNotePaie(List.of(bulletinAgent("422117", "500"), bulletinAgent(null, "300"))))
            .extracting(LigneNoteFraisRequest::compteImputation)
            .containsExactly("422117", "42211");
    }

    /** Le repli au paiement (mode manuel sans comptabilisation) crédite les comptes que la note a débités. */
    @Test
    void repliAuPaiementCrediteLeCompteDeLAgent() {
        NoteFrais n = note(CategorieNote.PAIE, StatutNote.TRANSMISE_CAISSE, "500");
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(List.of(bulletinAgent("422117", "500")));
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any()))
            .thenReturn(PieceComptable.builder().reference("PC-5").build());

        service.apresPaiementInterne(n, new User());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EcritureGrandLivre>> lignes = ArgumentCaptor.forClass(List.class);
        verify(comptabilite).creerPieceInterne(eq(JournalComptable.PAIE), anyString(), any(), lignes.capture(), any());
        assertThat(lignes.getValue()).anyMatch(l -> "422117".equals(l.getCompte().getNumero())
            && l.getCredit().compareTo(new BigDecimal("500")) == 0);
    }

    @Test
    void etatSignaleUnMoisClotureNonComptabilise() {
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(bulletin(StatutBulletin.VALIDE, true, "500")));
        assertThat(service.etat(9, 2026).comptabilisee()).isFalse();

        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setPieceComptable(pieceCreditant("42211"));
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(b));
        assertThat(service.etat(9, 2026).comptabilisee()).isTrue();
    }

    // --- Audit DRH du 15/11/2026, D-07 et D-08 : la note fiscale débite les comptes crédités par la constatation ----

    /** Pièce de constatation qui crédite chaque compte du montant donné : {compte, montant, compte, montant…}. */
    private static PieceComptable pieceAvecCredits(String... compteEtMontant) {
        PieceComptable piece = PieceComptable.builder().reference("PC-11").build();
        for (int i = 0; i < compteEtMontant.length; i += 2) {
            piece.getLignes().add(EcritureGrandLivre.builder()
                .compte(CompteOHADA.builder().numero(compteEtMontant[i]).build())
                .debit(BigDecimal.ZERO).credit(new BigDecimal(compteEtMontant[i + 1])).build());
        }
        return piece;
    }

    /** Lignes de la note fiscale créée pour l'organisme, la note de paie du mois étant payée. */
    @SuppressWarnings("unchecked")
    private List<LigneNoteFraisRequest> lignesDeLaNoteFiscale(OrganismePaie organisme, List<BulletinPaie> lies) {
        when(notes.findNotesPaie(9, 2026)).thenReturn(List.of(note(CategorieNote.PAIE, StatutNote.PAYEE, "800")));
        when(bulletins.findByNoteFraisPaieId(7L)).thenReturn(lies);
        service.creerNoteImpot(9, 2026, organisme);
        ArgumentCaptor<List<LigneNoteFraisRequest>> lignes = ArgumentCaptor.forClass(List.class);
        verify(noteFraisService).creerEtSoumettreNotePaieInterne(eq(CategorieNote.IMPOT_PAIE), eq(9), eq(2026),
            eq(organisme), anyString(), anyString(), anyString(), lignes.capture());
        return lignes.getValue();
    }

    @Test
    void noteFiscaleCnssATroisLignesAuxComptesDeLaConstatation() {
        // Une seule pièce groupée pour deux bulletins : chaque compte n'est compté qu'une fois.
        PieceComptable piece = pieceAvecCredits("4313", "96", "4311", "62.40", "4312", "14.40",
            "4472", "60", "44281", "4.80", "44282", "28.80", "422111", "500");
        BulletinPaie a = bulletin(StatutBulletin.VALIDE, true, "500");
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "300");
        a.setPieceComptable(piece);
        b.setPieceComptable(piece);

        List<LigneNoteFraisRequest> lignes = lignesDeLaNoteFiscale(OrganismePaie.CNSS, List.of(a, b));

        assertThat(lignes).extracting(LigneNoteFraisRequest::compteImputation).containsExactly("4311", "4312", "4313");
        assertThat(lignes).extracting(LigneNoteFraisRequest::montant)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("62.40"), new BigDecimal("14.40"), new BigDecimal("96"));
    }

    @Test
    void noteFiscaleCnssDUnePaieAncienneSur4315() {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setPieceComptable(pieceAvecCredits("4315", "108", "4472", "30", "44781", "1", "44782", "10"));

        List<LigneNoteFraisRequest> lignes = lignesDeLaNoteFiscale(OrganismePaie.CNSS, List.of(b));

        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).compteImputation()).isEqualTo("4315");
        assertThat(lignes.get(0).montant()).isEqualByComparingTo("108");
    }

    @Test
    void noteFiscaleOnemSur44281EtAncienneSur44781() {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setPieceComptable(pieceAvecCredits("4313", "60", "44281", "3"));
        assertThat(lignesDeLaNoteFiscale(OrganismePaie.ONEM, List.of(b)))
            .extracting(LigneNoteFraisRequest::compteImputation).containsExactly("44281");
    }

    @Test
    void noteFiscaleInppAncienneSur44782() {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setPieceComptable(pieceAvecCredits("4315", "108", "44782", "10"));
        List<LigneNoteFraisRequest> lignes = lignesDeLaNoteFiscale(OrganismePaie.INPP, List.of(b));
        assertThat(lignes).extracting(LigneNoteFraisRequest::compteImputation).containsExactly("44782");
        assertThat(lignes.get(0).montant()).isEqualByComparingTo("10");
    }

    /** Sans pièce de constatation : repli sur le calcul des bulletins et les comptes actuels. */
    @Test
    void noteFiscaleSansPieceReplieSurLesComptesActuels() {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setCnssPatronalePension(new BigDecimal("30"));
        b.setCnssPatronalePf(new BigDecimal("39"));
        b.setCnssPatronaleRp(new BigDecimal("9"));

        List<LigneNoteFraisRequest> cnss = lignesDeLaNoteFiscale(OrganismePaie.CNSS, List.of(b));

        assertThat(cnss).extracting(LigneNoteFraisRequest::compteImputation).containsExactly("4311", "4312", "4313");
        assertThat(cnss.stream().map(LigneNoteFraisRequest::montant).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("108");
        assertThat(PaieNoteService.ventilation(List.of(b), OrganismePaie.ONEM)).containsOnlyKeys("44281");
        assertThat(PaieNoteService.ventilation(List.of(b), OrganismePaie.INPP)).containsOnlyKeys("44282");
    }

    @Test
    void etatAfficheLesComptesDeLaCnssParBranche() {
        BulletinPaie b = bulletin(StatutBulletin.VALIDE, true, "500");
        b.setPieceComptable(pieceAvecCredits("4313", "60", "4311", "39", "4312", "9"));
        when(bulletins.findByPeriode(9, 2026)).thenReturn(List.of(b));

        var cnss = service.etat(9, 2026).versements().stream()
            .filter(v -> v.organisme() == OrganismePaie.CNSS).findFirst().orElseThrow();

        assertThat(cnss.comptes()).containsExactly("4311", "4312", "4313");
        assertThat(cnss.compte()).isEqualTo("4311/4312/4313");
        assertThat(cnss.montant()).isEqualByComparingTo("108");
    }

    @Test
    void paiementDUneNoteOrdinaireSansEffet() {
        service.apresPaiementInterne(note(CategorieNote.STANDARD, StatutNote.TRANSMISE_CAISSE, "10"), new User());
        verifyNoInteractions(bulletins, comptabilite);
    }
}

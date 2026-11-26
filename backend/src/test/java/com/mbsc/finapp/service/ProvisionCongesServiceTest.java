package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.ProvisionConges;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.ProvisionCongesRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provision pour congés payés (audit DRH du 15/11/2026, D-05) : jours acquis (1 jour par mois entier de service,
 * + 1 jour par tranche de 5 ans d'ancienneté), droits, déduction des congés payés, pièce équilibrée et extourne.
 */
class ProvisionCongesServiceTest {

    private final EmployeRepository employes = mock(EmployeRepository.class);
    private final BulletinPaieRepository bulletins = mock(BulletinPaieRepository.class);
    private final ProvisionCongesRepository provisions = mock(ProvisionCongesRepository.class);
    private final ParametresPaieService parametres = mock(ParametresPaieService.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ProvisionCongesService service = new ProvisionCongesService(employes, bulletins, provisions,
        parametres, comptabilite, comptes, pieces, mock(PeriodeComptableService.class), currentUser,
        new ObjectMapper().findAndRegisterModules());

    private final ParametresPaie params = ParametresPaie.builder().joursOuvrablesStandard(26).build();
    private static final BigDecimal TAUX = new BigDecimal("0.13");
    private static final LocalDate ARRETE = LocalDate.of(2026, 12, 31);

    ProvisionCongesServiceTest() {
        when(parametres.get()).thenReturn(params);
        when(comptes.findByNumero(anyString()))
            .thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(i.getArgument(0)).build()));
        when(currentUser.requireUser()).thenReturn(new User());
        when(provisions.findFirstByAnneeAndStatut(anyInt(), anyString())).thenReturn(Optional.empty());
        when(provisions.save(any(ProvisionConges.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Employe employe(long id, String salaire, LocalDate embauche, boolean expatrie) {
        return Employe.builder().id(id).matricule("MBSC-00" + id).nomComplet("Agent " + id)
            .salaireBaseUsd(new BigDecimal(salaire)).dateEmbauche(embauche).expatrie(expatrie).actif(true).build();
    }

    @Test
    void anneeCompleteSansAnciennete() {
        // 12 mois x 1 jour = 12 jours ; 520/26 = 20 USD par jour -> 240 USD ; charges 240 x 13 % = 31.20.
        var l = ProvisionCongesService.calculerLigne(employe(1, "520", LocalDate.of(2024, 6, 1), false), 2026, ARRETE,
            params, BigDecimal.ZERO, TAUX);
        assertThat(l.moisService()).isEqualTo(12);
        assertThat(l.ancienneteAnnees()).isEqualTo(2);
        assertThat(l.joursAcquis()).isEqualByComparingTo("12");
        assertThat(l.salaireJournalier()).isEqualByComparingTo("20");
        assertThat(l.droitsAcquis()).isEqualByComparingTo("240");
        assertThat(l.provision()).isEqualByComparingTo("240");
        assertThat(l.charges()).isEqualByComparingTo("31.20");
    }

    @Test
    void embaucheEnCoursDAnnee() {
        // Embauché le 15/03/2026 : du 15/03 au 31/12 -> 9 mois entiers (le 10e n'est pas achevé) -> 9 jours.
        // 9 x 520 / 26 = 180.
        var l = ProvisionCongesService.calculerLigne(employe(2, "520", LocalDate.of(2026, 3, 15), false), 2026, ARRETE,
            params, BigDecimal.ZERO, TAUX);
        assertThat(l.moisService()).isEqualTo(9);
        assertThat(l.joursAcquis()).isEqualByComparingTo("9");
        assertThat(l.provision()).isEqualByComparingTo("180");
    }

    @Test
    void ancienneteDeDixAns() {
        // Embauché le 01/01/2016 : 10 ans au 31/12/2026 -> 2 tranches de 5 ans -> 2 jours de plus sur l'année entière.
        // 12 + 2 x 1 x 12/12 = 14 jours ; 14 x 520 / 26 = 280.
        var l = ProvisionCongesService.calculerLigne(employe(3, "520", LocalDate.of(2016, 1, 1), false), 2026, ARRETE,
            params, BigDecimal.ZERO, TAUX);
        assertThat(l.ancienneteAnnees()).isEqualTo(10);
        assertThat(l.joursAcquis()).isEqualByComparingTo("14");
        assertThat(l.droitsAcquis()).isEqualByComparingTo("280");
    }

    @Test
    void congesDejaPayesDeduitsEtProvisionJamaisNegative() {
        var l = ProvisionCongesService.calculerLigne(employe(4, "520", LocalDate.of(2024, 6, 1), false), 2026, ARRETE,
            params, new BigDecimal("100"), TAUX);
        assertThat(l.congesPayes()).isEqualByComparingTo("100");
        assertThat(l.provision()).isEqualByComparingTo("140");           // 240 - 100
        assertThat(l.charges()).isEqualByComparingTo("18.20");           // 140 x 13 %

        var trop = ProvisionCongesService.calculerLigne(employe(4, "520", LocalDate.of(2024, 6, 1), false), 2026,
            ARRETE, params, new BigDecimal("300"), TAUX);
        assertThat(trop.provision()).isEqualByComparingTo("0");
    }

    @Test
    void embaucheApresLArreteIgnoree() {
        assertThat(ProvisionCongesService.calculerLigne(employe(5, "520", LocalDate.of(2027, 1, 10), false), 2026,
            ARRETE, params, BigDecimal.ZERO, TAUX)).isNull();
    }

    private static Map<String, BigDecimal> parCompte(List<EcritureGrandLivre> lignes, boolean debit) {
        return lignes.stream().filter(l -> (debit ? l.getDebit() : l.getCredit()).signum() > 0)
            .collect(Collectors.toMap(l -> l.getCompte().getNumero(), l -> debit ? l.getDebit() : l.getCredit()));
    }

    private static BigDecimal total(List<EcritureGrandLivre> lignes, boolean debit) {
        return lignes.stream().map(l -> debit ? l.getDebit() : l.getCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @SuppressWarnings("unchecked")
    void constitutionEcritUnePieceEquilibreeEtSonExtourne() {
        Employe national = employe(1, "520", LocalDate.of(2024, 6, 1), false);
        Employe expatrie = employe(2, "1040", LocalDate.of(2016, 1, 1), true);
        when(employes.findByActifTrueOrderByNomComplet()).thenReturn(List.of(national, expatrie));
        BulletinPaie conge = BulletinPaie.builder().conge(new BigDecimal("40")).build();
        when(bulletins.findValidesParEmployeEtAnnee(1L, 2026)).thenReturn(List.of(conge));
        when(bulletins.findValidesParEmployeEtAnnee(2L, 2026)).thenReturn(List.of());
        PieceComptable piece = PieceComptable.builder().id(10L).reference("PC-10").build();
        PieceComptable extourne = PieceComptable.builder().id(11L).reference("PC-11").build();
        when(comptabilite.creerPieceInterne(eq(JournalComptable.PAIE), anyString(), eq(ARRETE), any(), any()))
            .thenReturn(piece);
        when(comptabilite.creerPieceInterne(eq(JournalComptable.PAIE), anyString(), eq(LocalDate.of(2027, 1, 1)),
            any(), any())).thenReturn(extourne);

        var resume = service.constituer(2026, null);

        // National : 240 - 40 = 200 ; charges 26. Expatrié : 14 jours x 1040/26 = 560 ; charges 72.80.
        assertThat(resume.montantConges()).isEqualByComparingTo("760");
        assertThat(resume.montantCharges()).isEqualByComparingTo("98.80");
        ArgumentCaptor<List<EcritureGrandLivre>> lignes = ArgumentCaptor.forClass(List.class);
        verify(comptabilite, times(2)).creerPieceInterne(eq(JournalComptable.PAIE), anyString(), any(),
            lignes.capture(), any());
        List<EcritureGrandLivre> provision = lignes.getAllValues().get(0);
        assertThat(total(provision, true)).isEqualByComparingTo(total(provision, false));
        assertThat(parCompte(provision, true)).containsEntry("6613", new BigDecimal("200.00"))
            .containsEntry("6623", new BigDecimal("560.00")).containsEntry("6641", new BigDecimal("26.00"))
            .containsEntry("6642", new BigDecimal("72.80"));
        assertThat(parCompte(provision, false).get("4281")).isEqualByComparingTo("760");
        assertThat(parCompte(provision, false).get("4382")).isEqualByComparingTo("98.80");

        List<EcritureGrandLivre> inverse = lignes.getAllValues().get(1);
        assertThat(total(inverse, true)).isEqualByComparingTo(total(inverse, false));
        assertThat(parCompte(inverse, true).get("4281")).isEqualByComparingTo("760");
        assertThat(parCompte(inverse, false).get("6613")).isEqualByComparingTo("200");
        assertThat(extourne.isExtourneOuverture()).isTrue();
        assertThat(extourne.getPieceOrigine()).isSameAs(piece);
        assertThat(resume.piece()).isEqualTo("PC-10");
        assertThat(resume.pieceExtourne()).isEqualTo("PC-11");
    }

    @Test
    void constitutionRefuseeSiDejaActive() {
        when(provisions.findFirstByAnneeAndStatut(2026, ProvisionConges.ACTIVE)).thenReturn(Optional.of(
            ProvisionConges.builder().annee(2026).piece(PieceComptable.builder().reference("PC-3").build()).build()));
        assertThatThrownBy(() -> service.constituer(2026, null))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("déjà constituée")
            .hasMessageContaining("PC-3");
    }

    @Test
    void dateDArreteHorsExerciceRefusee() {
        assertThatThrownBy(() -> service.simuler(2026, LocalDate.of(2027, 1, 31)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void annulationExtourneLesDeuxPieces() {
        ProvisionConges p = ProvisionConges.builder().id(3L).annee(2026).dateArrete(ARRETE)
            .piece(PieceComptable.builder().id(10L).build()).pieceExtourne(PieceComptable.builder().id(11L).build())
            .statut(ProvisionConges.ACTIVE).build();
        when(provisions.findById(3L)).thenReturn(Optional.of(p));

        service.annuler(3L);

        verify(comptabilite).annulerInterne(eq(11L), any());
        verify(comptabilite).annulerInterne(eq(10L), any());
        assertThat(p.getStatut()).isEqualTo(ProvisionConges.ANNULEE);
        assertThatThrownBy(() -> service.annuler(3L)).isInstanceOf(TransitionInvalideException.class);
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.StatutVente;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.VenteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reflet analytique des ventes (classe 9) : une pièce du journal ANALYTIQUE par vente validée, par activité ; jamais de
 * blocage de la vente ; contre-passation à l'annulation ; reprise des ventes restées sans reflet.
 */
class AnalytiqueVentesServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);

    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final PeriodeComptableService periodes = mock(PeriodeComptableService.class);
    private final VenteRepository ventes = mock(VenteRepository.class);
    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final AnalytiqueVentesService service = new AnalytiqueVentesService(comptes, comptabilite, periodes, ventes, pieces,
        mock(PlatformTransactionManager.class));

    private final User caissier = User.builder().id(20L).email("caisse@test").build();
    private final Map<String, CompteOHADA> plan = new HashMap<>();

    private final CompteOHADA c7011 = CompteOHADA.builder().id(711L).numero("7011").actif(true).imputable(true).build();
    private final CompteOHADA c7061 = CompteOHADA.builder().id(761L).numero("7061").actif(true).imputable(true).build();
    private final CompteOHADA c706 = CompteOHADA.builder().id(706L).numero("706").actif(true).imputable(true).build();
    private final CompteOHADA c571 = CompteOHADA.builder().id(571L).numero("571").actif(true).imputable(true).build();
    private final Article biere = Article.builder().id(1L).type(TypeArticle.BOISSON).compteProduit(c7011).build();
    private final Article plat = Article.builder().id(2L).type(TypeArticle.PLAT).compteProduit(c7061).build();
    private final Article livraison = Article.builder().id(3L).type(TypeArticle.SERVICE).compteProduit(c706).build();

    @BeforeEach
    void preparer() {
        for (String n : List.of("926", "927", "9811", "9812", "9821", "9822", "9831", "9832", "9841", "9842", "9851", "9852")) {
            plan.put(n, CompteOHADA.builder().id((long) n.hashCode()).numero(n).actif(true).imputable(true).build());
        }
        when(comptes.findByNumero(anyString())).thenAnswer(i -> Optional.ofNullable(plan.get(i.<String>getArgument(0))));
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any()))
            .thenAnswer(i -> PieceComptable.builder().id(900L).reference("PC-ANA").statut(StatutPiece.COMPTABILISEE).build());
    }

    private static EcritureGrandLivre gl(CompteOHADA c, String debit, String credit) {
        return EcritureGrandLivre.builder().compte(c).debit(new BigDecimal(debit)).credit(new BigDecimal(credit)).build();
    }

    /** Bière 10 + plat 20 + livraison 5 (USD HT), coûts de sortie 4 et 12 ; payée en caisse. */
    private Vente venteValidee() {
        PieceComptable pieceVente = PieceComptable.builder().id(500L).reference("PC-VTE").statut(StatutPiece.COMPTABILISEE)
            .createdBy(caissier).lignes(new ArrayList<>(List.of(gl(c571, "35.00", "0"), gl(c7011, "0", "10.00"),
                gl(c7061, "0", "20.00"), gl(c706, "0", "5.00")))).build();
        MouvementStock sortie = MouvementStock.builder().id(600L).lignes(new ArrayList<>(List.of(
            LigneMouvementStock.builder().article(biere).montant(new BigDecimal("4.00")).build(),
            LigneMouvementStock.builder().article(plat).montant(new BigDecimal("12.00")).build()))).build();
        Vente v = Vente.builder().id(1L).reference("VTE-2026-000001").dateVente(DATE).statut(StatutVente.VALIDEE)
            .tauxJournalier(new BigDecimal("2250")).piece(pieceVente).mouvement(sortie).createdBy(caissier).build();
        v.addLigne(LigneVente.builder().article(biere).montantHt(new BigDecimal("10.00")).build());
        v.addLigne(LigneVente.builder().article(plat).montantHt(new BigDecimal("20.00")).build());
        v.addLigne(LigneVente.builder().article(livraison).montantHt(new BigDecimal("5.00")).build());
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<EcritureGrandLivre> lignesDeLaPieceAnalytique() {
        ArgumentCaptor<List<EcritureGrandLivre>> lignes = ArgumentCaptor.forClass(List.class);
        verify(comptabilite).creerPieceInterne(eq(JournalComptable.ANALYTIQUE), eq("Analytique — vente VTE-2026-000001"),
            eq(DATE), lignes.capture(), eq(caissier));
        return lignes.getValue();
    }

    @Test
    void uneVenteValideeEstRefleteeParActiviteEnUnePieceEquilibree() {
        Vente v = venteValidee();

        service.refleterInterne(v, caissier);

        assertThat(lignesDeLaPieceAnalytique())
            .extracting(e -> e.getCompte().getNumero(), e -> e.getDebit().toPlainString(), e -> e.getCredit().toPlainString())
            .containsExactly(
                tuple("927", "35.00", "0"),      // produits réfléchis : tout le chiffre d'affaires HT
                tuple("9811", "0", "10.00"),     // Bar
                tuple("9821", "0", "20.00"),     // Cuisine
                tuple("9851", "0", "5.00"),      // Services
                tuple("9812", "4.00", "0"),      // coût du Bar
                tuple("9822", "12.00", "0"),     // coût de la Cuisine
                tuple("926", "0", "16.00"));     // charges réfléchies : tout le coût
        assertThat(v.getPieceAnalytique()).isNotNull();
        assertThat(v.getPieceAnalytique().getReference()).isEqualTo("PC-ANA");
    }

    @Test
    void lesEcrituresPortentLeTauxDeLaVente() {
        service.refleterInterne(venteValidee(), caissier);

        assertThat(lignesDeLaPieceAnalytique()).allSatisfy(e -> assertThat(e.getTauxApplique()).isEqualByComparingTo("2250"));
    }

    @Test
    void unCompteAbsentNeBloqueJamaisLaVente() {
        plan.remove("9821");
        Vente v = venteValidee();

        service.refleterInterne(v, caissier);

        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), anyList(), any());
        assertThat(v.getPieceAnalytique()).isNull();   // la reprise au démarrage la rattrapera
    }

    @Test
    void unCompteDevenuRegroupementOuDesactiveNeBloqueJamaisLaVente() {
        plan.get("9811").setImputable(false);
        service.refleterInterne(venteValidee(), caissier);
        plan.get("9811").setImputable(true);
        plan.get("926").setActif(false);
        service.refleterInterne(venteValidee(), caissier);

        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), anyList(), any());
    }

    @Test
    void uneVenteDansUnePeriodeClotureeNEstPasRefletee() {
        when(periodes.dateCloture()).thenReturn(DATE);
        Vente v = venteValidee();

        service.refleterInterne(v, caissier);

        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), anyList(), any());
        assertThat(v.getPieceAnalytique()).isNull();
    }

    @Test
    void uneErreurImprevueNeRemontePasJusquALaVente() {
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any())).thenThrow(new IllegalStateException("panne"));
        Vente v = venteValidee();

        service.refleterInterne(v, caissier);   // ne lève pas

        assertThat(v.getPieceAnalytique()).isNull();
    }

    @Test
    void lAnnulationDeLaVenteContrePasseSonReflet() {
        Vente v = venteValidee();
        v.setPieceAnalytique(PieceComptable.builder().id(77L).reference("PC-ANA").statut(StatutPiece.COMPTABILISEE).build());

        service.annulerRefletInterne(v, caissier);

        verify(comptabilite).annulerInterne(77L, caissier);
    }

    @Test
    void sansRefletOuRefletDejaAnnuleRienNEstContrePasse() {
        Vente sans = venteValidee();
        service.annulerRefletInterne(sans, caissier);
        Vente dejaAnnule = venteValidee();
        dejaAnnule.setPieceAnalytique(PieceComptable.builder().id(78L).reference("PC-X").statut(StatutPiece.ANNULEE).build());
        service.annulerRefletInterne(dejaAnnule, caissier);

        verify(comptabilite, never()).annulerInterne(any(), any());
    }

    @Test
    void laRepriseRefleteLesVentesValideesSansRefletEtLesLie() {
        Vente v = venteValidee();
        when(ventes.idsValideesSansRefletAnalytique()).thenReturn(List.of(1L));
        when(ventes.findWithLignesById(1L)).thenReturn(Optional.of(v));
        when(pieces.findWithLignesById(500L)).thenReturn(Optional.of(v.getPiece()));
        when(ventes.lierPieceAnalytique(eq(1L), any())).thenReturn(1);

        assertThat(service.reprendreVentesSansReflet()).isEqualTo(1);

        lignesDeLaPieceAnalytique();   // même pièce qu'à la validation, à la date de la vente, au nom de son auteur
        verify(ventes).lierPieceAnalytique(eq(1L), any(PieceComptable.class));
    }

    @Test
    void laRepriseNeTouchePasUneVenteDejaRefleteeNiUneVenteAnnulee() {
        Vente refletee = venteValidee();
        refletee.setPieceAnalytique(PieceComptable.builder().id(1L).build());
        Vente annulee = venteValidee();
        annulee.setId(2L);
        annulee.setStatut(StatutVente.ANNULEE);
        when(ventes.idsValideesSansRefletAnalytique()).thenReturn(List.of(1L, 2L));
        when(ventes.findWithLignesById(1L)).thenReturn(Optional.of(refletee));
        when(ventes.findWithLignesById(2L)).thenReturn(Optional.of(annulee));

        assertThat(service.reprendreVentesSansReflet()).isZero();

        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), anyList(), any());
    }

    @Test
    void uneVenteEnEchecNEmpechePasLaRepriseDesAutres() {
        Vente premiere = venteValidee();
        Vente seconde = venteValidee();
        seconde.setId(2L);
        when(ventes.idsValideesSansRefletAnalytique()).thenReturn(List.of(1L, 2L));
        when(ventes.findWithLignesById(1L)).thenReturn(Optional.of(premiere));
        when(ventes.findWithLignesById(2L)).thenReturn(Optional.of(seconde));
        when(pieces.findWithLignesById(500L)).thenReturn(Optional.of(premiere.getPiece()));
        // La première a été reflétée entre-temps par une autre opération : sa transaction est annulée, la seconde passe.
        when(ventes.lierPieceAnalytique(eq(1L), any())).thenReturn(0);
        when(ventes.lierPieceAnalytique(eq(2L), any())).thenReturn(1);

        assertThat(service.reprendreVentesSansReflet()).isEqualTo(1);
    }

    @Test
    void rienAReprendre() {
        when(ventes.idsValideesSansRefletAnalytique()).thenReturn(List.of());

        assertThat(service.reprendreVentesSansReflet()).isZero();
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sortie de stock avec un compte de charge imposé (boisson offerte ou périmée) : la marchandise sort de la même façon,
 * seul le compte débité change ; le compte de stock crédité reste celui de l'article.
 */
class StockServiceCompteChargeImposeTest {

    private StockService service;
    private ComptabiliteService comptabilite;
    private LotStockService lotStock;
    private StockNiveau niveau;

    private final Entrepot bar = Entrepot.builder().id(1L).code("BAR").build();
    private final Article coca = Article.builder().id(7L).code("BRAL-COCA-COLA-33CL").libelle("Coca Cola 33CL")
        .type(TypeArticle.BOISSON)
        .compteStock(CompteOHADA.builder().numero("31112").build())
        .compteCharge(CompteOHADA.builder().numero("60312").build())
        .build();

    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return type.cast(Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow());
    }

    /** 10 bouteilles valorisées 15,00 : CMP 1,50, une sortie de 2 vaut 3,00. */
    @BeforeEach
    void preparer() throws Exception {
        // StockService a une quinzaine de dépendances : chacune est simulée, seules celles de la sortie sont réglées.
        Constructor<?> constructeur = StockService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (StockService) constructeur.newInstance(arguments);
        comptabilite = trouver(arguments, ComptabiliteService.class);
        lotStock = trouver(arguments, LotStockService.class);
        MouvementStockRepository mouvements = trouver(arguments, MouvementStockRepository.class);
        StockNiveauRepository niveaux = trouver(arguments, StockNiveauRepository.class);

        when(trouver(arguments, ReferenceGenerator.class).pourMouvementStock()).thenReturn("MS-TEST");
        when(mouvements.save(any(MouvementStock.class))).thenAnswer(i -> i.getArgument(0));
        niveau = StockNiveau.builder().article(coca).entrepot(bar)
            .quantite(new BigDecimal("10")).valeurTotale(new BigDecimal("15.00")).valeurAchat(new BigDecimal("15.00")).build();
        when(niveaux.findByArticleAndEntrepot(coca, bar)).thenReturn(Optional.of(niveau));
        when(niveaux.save(any(StockNiveau.class))).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<EcritureGrandLivre> ecrituresDe(StockService.SortieVente demande) {
        service.enregistrerSortieVenteInterne(LocalDate.of(2026, 10, 12), "Sortie test", bar, List.of(demande), null);
        ArgumentCaptor<List<EcritureGrandLivre>> ecritures = ArgumentCaptor.forClass(List.class);
        verify(comptabilite).creerPieceInterne(eq(JournalComptable.STOCK), eq("Sortie test"), any(), ecritures.capture(), any());
        return ecritures.getValue();
    }

    @Test
    void sansCompteImposeLaSortieDebiteLeCompteDeChargeDeLArticle() {
        assertThat(ecrituresDe(new StockService.SortieVente(coca, new BigDecimal("2"))))
            .extracting(e -> e.getCompte().getNumero(), EcritureGrandLivre::getDebit, EcritureGrandLivre::getCredit)
            .containsExactly(
                tuple("60312", new BigDecimal("3.00"), BigDecimal.ZERO),
                tuple("31112", BigDecimal.ZERO, new BigDecimal("3.00")));
    }

    @Test
    void unCompteImposeRemplaceLeCompteDeChargeMaisPasLeCompteDeStock() {
        CompteOHADA boissonsOffertes = CompteOHADA.builder().numero("6276.1").libelle("Boissons offertes à la clientèle").build();

        assertThat(ecrituresDe(new StockService.SortieVente(coca, new BigDecimal("2"), null, boissonsOffertes)))
            .extracting(e -> e.getCompte().getNumero(), EcritureGrandLivre::getDebit, EcritureGrandLivre::getCredit)
            .containsExactly(
                tuple("6276.1", new BigDecimal("3.00"), BigDecimal.ZERO),
                tuple("31112", BigDecimal.ZERO, new BigDecimal("3.00")));
        // La marchandise sort comme pour une vente : au coût moyen, quantité et valeur du stock diminuées.
        assertThat(niveau.getQuantite()).isEqualByComparingTo("8");
        assertThat(niveau.getValeurTotale()).isEqualByComparingTo("12.00");
    }

    @Test
    void lesConstructeursCourtsNImposentAucunCompte() {
        assertThat(new StockService.SortieVente(coca, BigDecimal.ONE).compteCharge()).isNull();
        assertThat(new StockService.SortieVente(coca, BigDecimal.ONE, new BigDecimal("4")).compteCharge()).isNull();
    }

    @Test
    void lesConstructeursCourtsNeDesignentAucuneLigneDeNote() {
        assertThat(new StockService.SortieVente(coca, BigDecimal.ONE).ligneNoteOrigineId()).isNull();
        assertThat(new StockService.SortieVente(coca, BigDecimal.ONE, new BigDecimal("4")).ligneNoteOrigineId()).isNull();
        assertThat(new StockService.SortieVente(coca, BigDecimal.ONE, null, null).ligneNoteOrigineId()).isNull();
    }

    @Test
    void rendueSurLaLigneNeChangeQueLaLigneDeNote() {
        CompteOHADA compte = CompteOHADA.builder().numero("6276.1").build();
        StockService.SortieVente rendue = new StockService.SortieVente(coca, new BigDecimal("2"), new BigDecimal("4"), compte)
            .rendueSurLaLigne(55L);

        assertThat(rendue.ligneNoteOrigineId()).isEqualTo(55L);
        assertThat(rendue.article()).isSameAs(coca);
        assertThat(rendue.quantite()).isEqualByComparingTo("2");
        assertThat(rendue.coutImpose()).isEqualByComparingTo("4");
        assertThat(rendue.compteCharge()).isSameAs(compte);
    }

    @Test
    void laSortieDUnRetourDAchatDesigneLaLigneDeNoteAuxLotsSansChangerLesEcritures() {
        // Mêmes écritures qu'une sortie ordinaire : la désignation ne sert qu'au suivi de gestion des lots.
        assertThat(ecrituresDe(new StockService.SortieVente(coca, new BigDecimal("2")).rendueSurLaLigne(55L)))
            .extracting(e -> e.getCompte().getNumero(), EcritureGrandLivre::getDebit, EcritureGrandLivre::getCredit)
            .containsExactly(
                tuple("60312", new BigDecimal("3.00"), BigDecimal.ZERO),
                tuple("31112", BigDecimal.ZERO, new BigDecimal("3.00")));
        assertThat(niveau.getQuantite()).isEqualByComparingTo("8");
        verify(lotStock).consommer(eq(coca), eq(bar), eq(new BigDecimal("2")), any(LigneMouvementStock.class), eq(55L));
    }

    @Test
    void uneSortieOrdinaireNeDesigneAucuneLigneDeNote() {
        ecrituresDe(new StockService.SortieVente(coca, new BigDecimal("2")));

        verify(lotStock).consommer(eq(coca), eq(bar), eq(new BigDecimal("2")), any(LigneMouvementStock.class), isNull());
    }

    @Test
    void lEntreeParNoteDeFraisTransmetLaLigneDeLaNoteAuLot() {
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any()))
            .thenReturn(PieceComptable.builder().reference("PC-STOCK").build());
        LigneNoteFrais ligneNote = LigneNoteFrais.builder().id(55L).build();
        LocalDate date = LocalDate.of(2026, 10, 22);

        service.entreesDepuisNoteFraisInterne(date, "Paiement NF-2026-000011", List.of(
                new StockService.EntreeNoteFrais(coca, bar, new BigDecimal("10"), new BigDecimal("15.00"),
                    new BigDecimal("12.00"), "Dépôt Luwawa", ligneNote)),
            PieceComptable.builder().reference("PC-REGLEMENT").build(), null);

        verify(lotStock).enregistrerEntree(eq(coca), eq(bar), eq(date), eq("Dépôt Luwawa"), any(), any(), any(),
            any(LigneMouvementStock.class), eq(ligneNote));
    }

    @Test
    void lEntreeSansLigneDeNoteLaisseLeLotSansLien() {
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), anyList(), any()))
            .thenReturn(PieceComptable.builder().reference("PC-STOCK").build());
        LocalDate date = LocalDate.of(2026, 10, 22);

        service.entreesDepuisNoteFraisInterne(date, "Paiement", List.of(
                new StockService.EntreeNoteFrais(coca, bar, new BigDecimal("10"), new BigDecimal("15.00"),
                    new BigDecimal("12.00"), "Dépôt")),
            PieceComptable.builder().reference("PC-REGLEMENT").build(), null);

        verify(lotStock).enregistrerEntree(eq(coca), eq(bar), eq(date), eq("Dépôt"), any(), any(), any(),
            any(LigneMouvementStock.class), isNull());
    }
}

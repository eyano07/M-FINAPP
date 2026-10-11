package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EmballageBoisson;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.MouvementEmballage;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import com.mbsc.finapp.dto.restaurant.MouvementEmballageRequest;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.MouvementEmballageRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saisie d'une perte de boisson (mouvements de bouteilles vides) : la boisson sort du stock au compte de sa perte —
 * 6276.1 pour une boisson offerte, 6031.2 pour une boisson périmée, le compte de la boisson pour une casse.
 */
class PertesBoissonsComptesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);

    private RestaurantService service;
    private StockService stock;
    private CompteOHADARepository comptes;
    private EmballageBoisson emballage;

    private final Entrepot entrepot = Entrepot.builder().id(1L).code("ENT-01").nom("Entrepôt Principal").build();
    private final Article coca = Article.builder().id(7L).code("BRAL-COCA-COLA-33CL").libelle("Coca Cola 33CL")
        .type(TypeArticle.BOISSON).build();
    private final CompteOHADA boissonsOffertes = CompteOHADA.builder().id(901L).numero("6276.1")
        .libelle("Boissons offertes à la clientèle").build();
    private final CompteOHADA boissonsPerimees = CompteOHADA.builder().id(902L).numero("6031.2")
        .libelle("Boissons périmées").build();

    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return type.cast(Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow());
    }

    @BeforeEach
    void preparer() throws Exception {
        // RestaurantService a une trentaine de dépendances : chacune est simulée, seules celles du test sont réglées.
        Constructor<?> constructeur = RestaurantService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (RestaurantService) constructeur.newInstance(arguments);
        stock = trouver(arguments, StockService.class);
        comptes = trouver(arguments, CompteOHADARepository.class);
        EmballageBoissonRepository emballages = trouver(arguments, EmballageBoissonRepository.class);
        MouvementEmballageRepository mouvementsVides = trouver(arguments, MouvementEmballageRepository.class);
        EntrepotRepository entrepots = trouver(arguments, EntrepotRepository.class);
        CurrentUserProvider utilisateur = trouver(arguments, CurrentUserProvider.class);

        when(utilisateur.requireUser()).thenReturn(User.builder().id(5L).prenom("Papy").nom("Penze").email("rest@mbsc.cd").build());
        emballage = EmballageBoisson.builder().id(3L).code("EMB-COCA").libelle("Casier Coca 33CL")
            .articleBoisson(coca).contenanceCasier(24).bouteillesVides(10).build();
        when(emballages.findByIdPourMiseAJour(3L)).thenReturn(Optional.of(emballage));
        when(mouvementsVides.save(any(MouvementEmballage.class))).thenAnswer(i -> i.getArgument(0));
        when(entrepots.findById(1L)).thenReturn(Optional.of(entrepot));
        when(comptes.findByNumero("6276.1")).thenReturn(Optional.of(boissonsOffertes));
        when(comptes.findByNumero("6031.2")).thenReturn(Optional.of(boissonsPerimees));
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any()))
            .thenReturn(MouvementStock.builder().reference("MS-2026-000070").build());
    }

    private static MouvementEmballageRequest perte(TypeMouvementEmballage type) {
        return new MouvementEmballageRequest(3L, type, 2, JOUR, "Client fidèle", 1L);
    }

    /** La sortie de stock demandée pour une perte de 2 bouteilles, et son libellé. */
    @SuppressWarnings("unchecked")
    private StockService.SortieVente sortieDemandee(TypeMouvementEmballage type, ArgumentCaptor<String> libelle) {
        service.enregistrerMouvement(perte(type));
        ArgumentCaptor<List<StockService.SortieVente>> sorties = ArgumentCaptor.forClass(List.class);
        verify(stock).enregistrerSortieVenteInterne(eq(JOUR), libelle.capture(), eq(entrepot), sorties.capture(), any());
        assertThat(sorties.getValue()).hasSize(1);
        StockService.SortieVente sortie = sorties.getValue().get(0);
        assertThat(sortie.article()).isSameAs(coca);
        assertThat(sortie.quantite()).isEqualByComparingTo("2");
        assertThat(sortie.coutImpose()).isNull();
        return sortie;
    }

    @Test
    void uneBoissonOfferteSortDuStockAuCompteDesBoissonsOffertes() {
        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);

        StockService.SortieVente sortie = sortieDemandee(TypeMouvementEmballage.CADEAU, libelle);

        assertThat(sortie.compteCharge()).isSameAs(boissonsOffertes);
        assertThat(libelle.getValue()).contains("Boisson offerte (cadeau)").contains("Coca Cola 33CL");
        assertThat(emballage.getBouteillesVides()).isEqualTo(12);   // la bouteille vide revient dans le stock de vides
    }

    @Test
    void uneBoissonPerimeeSortDuStockAuCompteDesBoissonsPerimees() {
        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);

        StockService.SortieVente sortie = sortieDemandee(TypeMouvementEmballage.PERIME, libelle);

        assertThat(sortie.compteCharge()).isSameAs(boissonsPerimees);
        assertThat(libelle.getValue()).contains("Boisson périmée");
        assertThat(emballage.getBouteillesVides()).isEqualTo(12);
    }

    @Test
    void laCasseDUneBouteillePleineGardeLeCompteDeLaBoisson() {
        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);

        StockService.SortieVente sortie = sortieDemandee(TypeMouvementEmballage.CASSE_PLEINE, libelle);

        assertThat(sortie.compteCharge()).isNull();
        verify(comptes, never()).findByNumero(anyString());
        assertThat(emballage.getBouteillesVides()).isEqualTo(8);
    }

    @Test
    void sansLeCompteAuPlanComptableLaPerteEstRefuseePlutotQuePasseeSurLeCompteDeLaBoisson() {
        when(comptes.findByNumero("6276.1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enregistrerMouvement(perte(TypeMouvementEmballage.CADEAU)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("6276.1")
            .hasMessageContaining("Boissons offertes à la clientèle")
            .hasMessageContaining("Plan comptable");
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
    }

    @Test
    void sansLeCompteDesBoissonsPerimeesLaPeremptionEstRefuseeAussi() {
        when(comptes.findByNumero("6031.2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enregistrerMouvement(perte(TypeMouvementEmballage.PERIME)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("6031.2")
            .hasMessageContaining("Boissons périmées")
            .hasMessageContaining("sous 6031");
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.SortieProvision;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeSortieProvision;
import com.mbsc.finapp.dto.restaurant.ProvisionSortieRequest;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.SortieProvisionRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sortie d'une provision : un type choisi dans une liste, une observation libre — obligatoire pour « Autre » —,
 * conservés avec la sortie de stock pour l'historique.
 */
class SortieProvisionTest {

    private ArticleRepository articles;
    private EntrepotRepository entrepots;
    private StockService stock;
    private SortieProvisionRepository sorties;
    private RestaurantService service;
    private Article riz;
    private Entrepot entrepot;

    @BeforeEach
    void preparer() throws Exception {
        // RestaurantService a une trentaine de dépendances : chacune est simulée, seules celles de la sortie sont réglées.
        Constructor<?> constructeur = RestaurantService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (RestaurantService) constructeur.newInstance(arguments);
        articles = trouver(arguments, ArticleRepository.class);
        entrepots = trouver(arguments, EntrepotRepository.class);
        stock = trouver(arguments, StockService.class);
        sorties = trouver(arguments, SortieProvisionRepository.class);
        CurrentUserProvider utilisateur = trouver(arguments, CurrentUserProvider.class);

        User auteur = User.builder().id(5L).prenom("Papy").nom("Penze").email("rest@mbsc.cd").build();
        when(utilisateur.requireUser()).thenReturn(auteur);
        riz = Article.builder().id(10L).code("PROV-RIZ").libelle("Riz").type(TypeArticle.PROVISION).build();
        entrepot = Entrepot.builder().id(1L).code("ENT-01").build();
        when(articles.findById(10L)).thenReturn(Optional.of(riz));
        when(entrepots.findById(1L)).thenReturn(Optional.of(entrepot));
        when(stock.enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any()))
            .thenReturn(MouvementStock.builder().id(77L).reference("MS-2026-000077").build());
    }

    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return type.cast(Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow());
    }

    private static ProvisionSortieRequest sortie(TypeSortieProvision type, String observation) {
        return new ProvisionSortieRequest(10L, new BigDecimal("2.5"), 1L, type, observation, LocalDate.of(2026, 10, 11));
    }

    @Test
    void laSortieGardeSonTypeEtSonObservation() {
        service.enregistrerSortieProvision(sortie(TypeSortieProvision.PEREMPTION, "  sac resté ouvert  "));

        ArgumentCaptor<SortieProvision> enregistree = ArgumentCaptor.forClass(SortieProvision.class);
        verify(sorties).save(enregistree.capture());
        assertThat(enregistree.getValue().getTypeSortie()).isEqualTo(TypeSortieProvision.PEREMPTION);
        assertThat(enregistree.getValue().getObservation()).isEqualTo("sac resté ouvert");
        assertThat(enregistree.getValue().getMouvementStock().getReference()).isEqualTo("MS-2026-000077");
        assertThat(enregistree.getValue().getCreatedBy().getEmail()).isEqualTo("rest@mbsc.cd");
    }

    @Test
    void leLibelleDuMouvementDitLeTypeLaProvisionEtL_observation() {
        service.enregistrerSortieProvision(sortie(TypeSortieProvision.REPAS_PERSONNEL, "déjeuner de l'équipe"));

        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);
        verify(stock).enregistrerSortieVenteInterne(any(), libelle.capture(), any(), any(), any());
        assertThat(libelle.getValue()).isEqualTo("Sortie provision — Repas du personnel — Riz (déjeuner de l'équipe)");
    }

    @Test
    void uneObservationVideEstFacultativePourUnTypeOrdinaire() {
        service.enregistrerSortieProvision(sortie(TypeSortieProvision.UTILISATION_CUISINE, "   "));

        ArgumentCaptor<SortieProvision> enregistree = ArgumentCaptor.forClass(SortieProvision.class);
        verify(sorties).save(enregistree.capture());
        assertThat(enregistree.getValue().getObservation()).isNull();
        ArgumentCaptor<String> libelle = ArgumentCaptor.forClass(String.class);
        verify(stock).enregistrerSortieVenteInterne(any(), libelle.capture(), any(), any(), any());
        assertThat(libelle.getValue()).isEqualTo("Sortie provision — Utilisation en cuisine — Riz");
    }

    @Test
    void leTypeAutreExigeUneObservationEtRienN_estEnregistre() {
        for (String observation : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.enregistrerSortieProvision(sortie(TypeSortieProvision.AUTRE, observation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Précisez la raison");
        }
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
        verify(sorties, never()).save(any());

        service.enregistrerSortieProvision(sortie(TypeSortieProvision.AUTRE, "échantillon pour le fournisseur"));
        verify(sorties).save(any(SortieProvision.class));
    }

    @Test
    void seuleUneProvisionPeutSortirParCetEcran() {
        Article boisson = Article.builder().id(11L).code("BRAC-PRIM-55CL").libelle("Primus 55CL").type(TypeArticle.BOISSON).build();
        when(articles.findById(11L)).thenReturn(Optional.of(boisson));

        assertThatThrownBy(() -> service.enregistrerSortieProvision(
            new ProvisionSortieRequest(11L, BigDecimal.ONE, 1L, TypeSortieProvision.CASSE, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("n'est pas une provision");
        verify(stock, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), any(), any());
    }

    @Test
    void sansDateLaSortieEstDuJour() {
        service.enregistrerSortieProvision(
            new ProvisionSortieRequest(10L, BigDecimal.ONE, 1L, TypeSortieProvision.DON, null, null));

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(stock).enregistrerSortieVenteInterne(date.capture(), anyString(), any(), any(), any());
        assertThat(date.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void lesSortiesDeLaListeSontToutesAcceptees() {
        for (TypeSortieProvision type : TypeSortieProvision.values()) {
            String observation = type == TypeSortieProvision.AUTRE ? "précision" : null;
            service.enregistrerSortieProvision(sortie(type, observation));
        }
        verify(sorties, org.mockito.Mockito.times(TypeSortieProvision.values().length)).save(any(SortieProvision.class));
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.dto.budget.ExecutionServiceResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.SuiviService;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.budget.ServiceBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ServiceBudgetaireResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Services budgetaires : nom unique a la casse pres, responsable obligatoire, suppression d'un service jamais utilise. */
class ServiceBudgetaireServiceTest {

    private ServiceBudgetaireRepository repository;
    private ServiceBudgetaireService service;
    private BudgetRepository budgets;
    private SuiviBudgetaireService suivi;

    @BeforeEach
    void preparer() {
        repository = mock(ServiceBudgetaireRepository.class);
        CurrentUserProvider courant = mock(CurrentUserProvider.class);
        when(courant.requireUser()).thenReturn(User.builder().id(7L).prenom("Bruno").nom("Kalunga").email("dfin_bruno").build());
        when(repository.save(any(ServiceBudgetaire.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.compterLignesParService()).thenReturn(List.of());
        when(repository.compterNotesParService()).thenReturn(List.of());
        budgets = mock(BudgetRepository.class);
        suivi = mock(SuiviBudgetaireService.class);
        service = new ServiceBudgetaireService(repository, courant, budgets, suivi);
    }

    @Test
    void creation_avec_le_nom_du_responsable_espaces_nettoyes() {
        ServiceBudgetaireResponse r = service.creer(new ServiceBudgetaireRequest("  Direction   financière ", " Brino  Kalunga ", "  "));
        ArgumentCaptor<ServiceBudgetaire> cree = ArgumentCaptor.forClass(ServiceBudgetaire.class);
        verify(repository).save(cree.capture());
        assertThat(cree.getValue().getNom()).isEqualTo("Direction financière");
        assertThat(cree.getValue().getResponsable()).isEqualTo("Brino Kalunga");
        assertThat(cree.getValue().getDescription()).isNull();
        assertThat(cree.getValue().isActif()).isTrue();
        assertThat(r.creeParNom()).isEqualTo("Bruno Kalunga");
    }

    @Test
    void deux_services_ne_portent_pas_le_meme_nom() {
        when(repository.existsByNomIgnoreCase("Restaurant")).thenReturn(true);
        assertThatThrownBy(() -> service.creer(new ServiceBudgetaireRequest("Restaurant", "Jean", null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).contains("« Restaurant »");
            });
        verify(repository, never()).save(any());
    }

    @Test
    void un_service_utilise_ne_se_supprime_pas_il_se_desactive() {
        ServiceBudgetaire s = ServiceBudgetaire.builder().id(4L).nom("Logistique").responsable("Marie").actif(true).build();
        when(repository.findById(4L)).thenReturn(Optional.of(s));
        when(repository.compterNotesParService()).thenReturn(List.<Object[]>of(new Object[] {4L, 3L}));
        assertThatThrownBy(() -> service.supprimer(4L))
            .isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("0 ligne(s) de budget, 3 note(s) de frais").hasMessageContaining("désactivez-le");
        verify(repository, never()).delete(any());

        assertThat(service.basculerActif(4L).actif()).isFalse();
        assertThat(service.basculerActif(4L).actif()).isTrue();
    }

    @Test
    void un_service_jamais_utilise_se_supprime() {
        ServiceBudgetaire s = ServiceBudgetaire.builder().id(5L).nom("Essai").responsable("Moi").build();
        when(repository.findById(5L)).thenReturn(Optional.of(s));
        service.supprimer(5L);
        verify(repository).delete(s);
    }

    @Test
    void renommer_vers_un_nom_deja_pris_est_refuse() {
        ServiceBudgetaire s = ServiceBudgetaire.builder().id(6L).nom("Atelier").responsable("Moi").build();
        when(repository.findById(6L)).thenReturn(Optional.of(s));
        when(repository.existsByNomIgnoreCaseAndIdNot("Restaurant", 6L)).thenReturn(true);
        assertThatThrownBy(() -> service.modifier(6L, new ServiceBudgetaireRequest("Restaurant", "Moi", null)))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.modifier(6L, new ServiceBudgetaireRequest("Atelier mécanique", "Paul Ilunga", "Garage")).responsable())
            .isEqualTo("Paul Ilunga");
    }

    @Test
    void execution_d_un_service_lue_dans_le_budget_en_cours_plutot_que_dans_un_brouillon() {
        ServiceBudgetaire info = ServiceBudgetaire.builder().id(3L).nom("Service Informatique").responsable("Pascal").actif(true).build();
        int annee = LocalDate.now().getYear();
        Budget brouillon = budget(10L, annee + 1, StatutBudget.BROUILLON, info);
        Budget enCours = budget(11L, annee, StatutBudget.EN_EXECUTION, info);
        when(budgets.findAll()).thenReturn(List.of(brouillon, enCours));
        SuiviService exec = new SuiviService(3L, "Service Informatique", "Pascal", true, List.of(), new BigDecimal("1000"),
            new BigDecimal("250"), new BigDecimal("100"), new BigDecimal("650"), new BigDecimal("25.00"), BigDecimal.ZERO,
            BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), List.of());
        when(suivi.executionDesServices(eq(enCours), any(), any(), anyMap())).thenReturn(Map.of(3L, exec));

        List<ExecutionServiceResponse> r = service.executions();

        assertThat(r).singleElement().satisfies(e -> {
            assertThat(e.budgetId()).isEqualTo(11L);
            assertThat(e.periode()).isEqualTo("Exercice " + annee);
            assertThat(e.prevu()).isEqualByComparingTo("1000");
            assertThat(e.tauxExecution()).isEqualByComparingTo("25");
            assertThat(e.joursEcoules()).isBetween(1L, e.joursTotal());
        });
        verify(suivi, never()).executionDesServices(eq(brouillon), any(), any(), anyMap());
        verify(suivi).mouvementsDetailles(annee);
        verify(suivi).engagementsDeLExercice(eq(annee), isNull(), anyList());
    }

    @Test
    void un_service_absent_de_tout_budget_n_a_pas_d_execution() {
        when(budgets.findAll()).thenReturn(List.of());
        assertThat(service.executions()).isEmpty();
        verify(suivi, never()).mouvementsDetailles(anyInt());
    }

    private static Budget budget(Long id, int exercice, StatutBudget statut, ServiceBudgetaire s) {
        Budget b = Budget.builder().id(id).reference("BUD-" + id).intitule("Budget " + exercice).exercice(exercice)
            .duree(DureeBudget.ANNUEL).periode(1).statut(statut).lignes(new ArrayList<>()).build();
        b.getLignes().add(LigneBudget.builder().budget(b).service(s).compte(CompteOHADA.builder().numero("6051").build())
            .montantPrevu(new BigDecimal("1000")).build());
        return b;
    }
}

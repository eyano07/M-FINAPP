package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ServiceBudgetaire;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Services budgetaires : nom unique a la casse pres, responsable obligatoire, suppression d'un service jamais utilise. */
class ServiceBudgetaireServiceTest {

    private ServiceBudgetaireRepository repository;
    private ServiceBudgetaireService service;

    @BeforeEach
    void preparer() {
        repository = mock(ServiceBudgetaireRepository.class);
        CurrentUserProvider courant = mock(CurrentUserProvider.class);
        when(courant.requireUser()).thenReturn(User.builder().id(7L).prenom("Bruno").nom("Kalunga").email("dfin_bruno").build());
        when(repository.save(any(ServiceBudgetaire.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.compterLignesParService()).thenReturn(List.of());
        when(repository.compterNotesParService()).thenReturn(List.of());
        service = new ServiceBudgetaireService(repository, courant);
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
}

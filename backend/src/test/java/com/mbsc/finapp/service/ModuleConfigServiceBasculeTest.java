package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.parametrage.ModuleConfigResponse;
import com.mbsc.finapp.dto.parametrage.ModuleImpactResponse;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Basculer un module : verrouille les modules, ecrit l'etat, met les comptes en conformite dans la foulee, et dit
 * combien de comptes ont ete touches. L'apercu ne fait que relayer la simulation.
 */
class ModuleConfigServiceBasculeTest {

    private ModuleConfigRepository repository;
    private SynchronisationComptesModules synchronisation;
    private ModuleConfigService service;

    @BeforeEach
    void preparer() {
        repository = mock(ModuleConfigRepository.class);
        synchronisation = mock(SynchronisationComptesModules.class);
        service = new ModuleConfigService(repository, synchronisation);
        when(repository.findById(ModuleMetier.RESTAURANT))
            .thenReturn(Optional.of(ModuleConfig.builder().module(ModuleMetier.RESTAURANT).actif(true).build()));
        when(repository.save(any(ModuleConfig.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void couper_un_module_ecrit_l_etat_puis_met_les_comptes_en_conformite_dans_cet_ordre() {
        when(synchronisation.reconcilier()).thenReturn(new SynchronisationComptesModules.Resultat(List.of("a", "b", "c"), List.of()));

        ModuleConfigResponse reponse = service.definir(ModuleMetier.RESTAURANT, false);

        assertThat(reponse.actif()).isFalse();
        assertThat(reponse.comptesDesactives()).isEqualTo(3);
        assertThat(reponse.comptesReactives()).isZero();
        InOrder ordre = inOrder(repository, synchronisation);
        ordre.verify(repository).findAllPourMiseAJour();
        ordre.verify(repository).save(any(ModuleConfig.class));
        ordre.verify(synchronisation).reconcilier();
    }

    @Test
    void reactiver_un_module_annonce_les_comptes_remis_en_service() {
        when(synchronisation.reconcilier()).thenReturn(new SynchronisationComptesModules.Resultat(List.of(), List.of("a", "b")));

        ModuleConfigResponse reponse = service.definir(ModuleMetier.RESTAURANT, true);

        assertThat(reponse.actif()).isTrue();
        assertThat(reponse.comptesDesactives()).isZero();
        assertThat(reponse.comptesReactives()).isEqualTo(2);
    }

    @Test
    void la_liste_des_modules_n_a_pas_de_compteurs() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.lister()).isNotEmpty().allSatisfy(m -> {
            assertThat(m.comptesDesactives()).isNull();
            assertThat(m.comptesReactives()).isNull();
        });
    }

    @Test
    void l_apercu_relaie_la_simulation_sans_ecrire() {
        when(synchronisation.simuler(ModuleMetier.RESTAURANT, false)).thenReturn(new SynchronisationComptesModules.Impact(
            List.of(RoleType.RESP_BAR), List.of("Prenom Nom (compte)"), List.of()));

        ModuleImpactResponse impact = service.impact(ModuleMetier.RESTAURANT, false);

        assertThat(impact.module()).isEqualTo(ModuleMetier.RESTAURANT);
        assertThat(impact.actif()).isFalse();
        assertThat(impact.roles()).containsExactly(RoleType.RESP_BAR);
        assertThat(impact.comptesADesactiver()).containsExactly("Prenom Nom (compte)");
        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}

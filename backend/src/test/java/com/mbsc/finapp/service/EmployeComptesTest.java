package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.drh.EmployeRequest;
import com.mbsc.finapp.dto.drh.EmployeResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmployeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Comptes de paie de la fiche employé (audit DRH du 15/11/2026, D-02, D-03 et D-06). */
class EmployeComptesTest {

    private final EmployeRepository employes = mock(EmployeRepository.class);
    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final EmployeService service = new EmployeService(employes, comptes);

    private final CompteOHADA collectif = CompteOHADA.builder().id(1L).numero("42211")
        .libelle("Personnel, rémunérations dues — salaires nets").type(TypeCompte.PASSIF).classe(4).build();
    private final CompteOHADA avances = CompteOHADA.builder().id(2L).numero("4211")
        .libelle("Personnel, avances").type(TypeCompte.ACTIF).classe(4).build();

    EmployeComptesTest() {
        when(comptes.findByNumero("42211")).thenReturn(Optional.of(collectif));
        when(comptes.findByNumero("4211")).thenReturn(Optional.of(avances));
        Set<String> pris = Set.of("422111", "422112", "42111");
        when(comptes.existsByNumero(anyString())).thenAnswer(i -> pris.contains(i.<String>getArgument(0)));
        when(comptes.save(any(CompteOHADA.class))).thenAnswer(i -> i.getArgument(0));
        when(employes.save(any(Employe.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static EmployeRequest requete(String nom, String comptePret) {
        return new EmployeRequest(nom, null, null, null, null, null, null, null, new BigDecimal("500"), null, 0,
            null, 0, BigDecimal.ZERO, true, false, false, comptePret);
    }

    @Test
    void creationOuvreLeCompteDeLAgentAuPremierNumeroLibre() {
        EmployeResponse r = service.creer(requete("Jean Kabila", null));

        assertThat(r.compteRemuneration()).isEqualTo("422113");
        ArgumentCaptor<CompteOHADA> compte = ArgumentCaptor.forClass(CompteOHADA.class);
        verify(comptes, org.mockito.Mockito.times(2)).save(compte.capture());
        CompteOHADA c = compte.getAllValues().get(0);
        assertThat(c.getLibelle()).isEqualTo("Rémunérations dues : Jean Kabila");
        assertThat(c.getParent()).isSameAs(collectif);
        assertThat(c.getType()).isEqualTo(TypeCompte.PASSIF);
        assertThat(c.getClasse()).isEqualTo(4);
        assertThat(c.isImputable()).isTrue();
        assertThat(c.isActif()).isTrue();
        assertThat(c.isManuel()).isTrue();
    }

    /** D-06 : la création ouvre aussi le compte d'avances de l'agent sous 4211, au premier numéro libre. */
    @Test
    void creationOuvreLeCompteDAvancesSous4211() {
        EmployeResponse r = service.creer(requete("Jean Kabila", null));

        assertThat(r.compteAvance()).isEqualTo("42112");
        ArgumentCaptor<CompteOHADA> compte = ArgumentCaptor.forClass(CompteOHADA.class);
        verify(comptes, org.mockito.Mockito.times(2)).save(compte.capture());
        CompteOHADA c = compte.getAllValues().get(1);
        assertThat(c.getNumero()).isEqualTo("42112");
        assertThat(c.getLibelle()).isEqualTo("Avances : Jean Kabila");
        assertThat(c.getParent()).isSameAs(avances);
        assertThat(c.getType()).isEqualTo(TypeCompte.ACTIF);
        assertThat(c.isImputable()).isTrue();
        assertThat(c.isActif()).isTrue();
    }

    @Test
    void lIntituleDuCompteSuitLeNomDeLAgent() {
        CompteOHADA compteAgent = CompteOHADA.builder().numero("422111").libelle("Rémunérations dues : Ancien")
            .parent(collectif).build();
        CompteOHADA compteAvance = CompteOHADA.builder().numero("42111").libelle("Avances : Ancien")
            .parent(avances).build();
        when(comptes.findByNumero("422111")).thenReturn(Optional.of(compteAgent));
        when(comptes.findByNumero("42111")).thenReturn(Optional.of(compteAvance));
        Employe e = Employe.builder().id(5L).matricule("MBSC-005").nomComplet("Ancien").compteRemuneration("422111")
            .compteAvance("42111").build();
        when(employes.findById(5L)).thenReturn(Optional.of(e));

        service.modifier(5L, requete("Nouveau Nom", null));

        assertThat(compteAgent.getLibelle()).isEqualTo("Rémunérations dues : Nouveau Nom");
        assertThat(compteAvance.getLibelle()).isEqualTo("Avances : Nouveau Nom");
        assertThat(e.getCompteRemuneration()).isEqualTo("422111");
        verify(comptes, never()).save(any(CompteOHADA.class));
    }

    @Test
    void comptePretValide() {
        when(comptes.findByNumero("272891")).thenReturn(Optional.of(
            CompteOHADA.builder().numero("272891").imputable(true).build()));

        assertThat(service.creer(requete("Agent", " 272891 ")).comptePret()).isEqualTo("272891");
    }

    @Test
    void comptePretHorsDes272Refuse() {
        when(comptes.findByNumero("2762")).thenReturn(Optional.of(CompteOHADA.builder().numero("2762").imputable(true).build()));

        assertThatThrownBy(() -> service.creer(requete("Agent", "2762")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("272");
    }

    @Test
    void comptePretInexistantOuNonImputableRefuse() {
        when(comptes.findByNumero("2728")).thenReturn(Optional.of(CompteOHADA.builder().numero("2728").imputable(false).build()));

        assertThatThrownBy(() -> service.creer(requete("Agent", "27299")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> service.creer(requete("Agent", "2728")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("imputable");
    }

    @Test
    void comptePretFacultatif() {
        assertThat(service.creer(requete("Agent", "  ")).comptePret()).isNull();
    }
}

package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.repository.CompteOHADARepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sous-comptes des projets : regroupement par projet (non imputable), puis un compte imputable par besoin. */
class ComptesProjetServiceTest {

    private final Map<String, CompteOHADA> plan = new HashMap<>();
    private final CompteOHADARepository repo = mock(CompteOHADARepository.class);
    private final ComptesProjetService service = new ComptesProjetService(repo);

    ComptesProjetServiceTest() {
        plan.put("6588", CompteOHADA.builder().id(1L).numero("6588").libelle("Autres charges diverses").type(TypeCompte.CHARGE).classe(6).build());
        plan.put("658801", CompteOHADA.builder().id(2L).numero("658801").libelle("Indemnités diverses").type(TypeCompte.CHARGE).classe(6).build());
        when(repo.findByNumero(anyString())).thenAnswer(i -> Optional.ofNullable(plan.get(i.<String>getArgument(0))));
        when(repo.existsByNumero(anyString())).thenAnswer(i -> plan.containsKey(i.<String>getArgument(0)));
        when(repo.saveAndFlush(any(CompteOHADA.class))).thenAnswer(i -> {
            CompteOHADA c = i.getArgument(0);
            c.setId((long) plan.size() + 10);
            plan.put(c.getNumero(), c);
            return c;
        });
    }

    @Test
    void le_prefixe_projet_designe_un_compte_de_projet() {
        assertThat(ComptesProjetService.estCompteProjet("Projet Puits — Ciment")).isTrue();
        assertThat(ComptesProjetService.estCompteProjet("  projet puits")).isTrue();
        assertThat(ComptesProjetService.estCompteProjet("Autres charges diverses")).isFalse();
        assertThat(ComptesProjetService.estCompteProjet(null)).isFalse();
    }

    @Test
    void une_acquisition_va_en_6058_ou_en_immobilisation() {
        assertThat(ComptesProjetService.parentAcquisitionAdmis("6058")).isTrue();
        assertThat(ComptesProjetService.parentAcquisitionAdmis("2441")).isTrue();
        assertThat(ComptesProjetService.parentAcquisitionAdmis("2411")).isTrue();
        assertThat(ComptesProjetService.parentAcquisitionAdmis("6588")).isFalse();
        assertThat(ComptesProjetService.parentAcquisitionAdmis("2844")).isFalse();
    }

    @Test
    void besoin_sous_un_regroupement_projets_partage_sans_nom_de_projet() {
        CompteOHADA ciment = service.compteDuBesoin("6588", "Ciment 50 kg");
        CompteOHADA sable = service.compteDuBesoin("6588", " Sable ");
        // 658801 est un compte ordinaire (imputable) : le regroupement « Projets » est ouvert en 658802.
        assertThat(plan.get("658802").getLibelle()).isEqualTo("Projets");
        assertThat(plan.get("658802").isImputable()).isFalse();
        assertThat(ciment.getNumero()).isEqualTo("65880201");
        assertThat(sable.getNumero()).isEqualTo("65880202");
        assertThat(ciment.getLibelle()).isEqualTo("Projet — Ciment 50 kg");
        assertThat(sable.getLibelle()).isEqualTo("Projet — Sable");
        assertThat(ciment.isImputable()).isTrue();
        assertThat(ciment.getParent()).isSameAs(plan.get("658802"));
        assertThat(ciment.getClasse()).isEqualTo(6);
    }

    @Test
    void un_ancien_regroupement_projet_est_reutilise() {
        plan.put("658801", CompteOHADA.builder().id(3L).numero("658801").libelle("Projets").imputable(false)
            .type(TypeCompte.CHARGE).classe(6).build());
        plan.put("65880101", CompteOHADA.builder().id(4L).numero("65880101").libelle("Projet — Riz").type(TypeCompte.CHARGE).classe(6).build());
        assertThat(service.compteDuBesoin("6588", "Harricot").getNumero()).isEqualTo("65880102");
    }
}

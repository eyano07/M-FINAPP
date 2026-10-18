package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.dto.comptabilite.EtatAnalytiqueResponse;
import com.mbsc.finapp.dto.comptabilite.LigneAnalytiqueResponse;
import com.mbsc.finapp.dto.comptabilite.SousCompteAnalytiqueRequest;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComptabiliteAnalytiqueServiceTest {

    private static final LocalDate DU = LocalDate.of(2026, 1, 1);
    private static final LocalDate AU = LocalDate.of(2026, 12, 31);

    private static CompteOHADA compte(long id, String numero, String libelle) {
        return CompteOHADA.builder().id(id).numero(numero).libelle(libelle).classe(9).build();
    }

    private static BigDecimal[] m(long debit, long credit) {
        return new BigDecimal[] {BigDecimal.valueOf(debit), BigDecimal.valueOf(credit)};
    }

    private static LigneAnalytiqueResponse ligne(EtatAnalytiqueResponse etat, String numero) {
        return etat.lignes().stream().filter(l -> l.numero().equals(numero)).findFirst().orElseThrow();
    }

    @Test
    void etat_cumuleLesSousComptesDansLaRubriqueEtCalculeLesSoldes() {
        List<CompteOHADA> comptes = List.of(
            compte(1, "92", "Comptes réfléchis"),
            compte(2, "94", "Comptes de coûts"),
            compte(3, "9411", "Coût atelier A"),
            compte(4, "9412", "Coût atelier B"),
            compte(5, "98", "Comptes de résultats"));

        EtatAnalytiqueResponse etat = ComptabiliteAnalytiqueService.construireEtat(DU, AU, comptes,
            Map.of("9411", m(100, 0), "92", m(0, 100)),
            Map.of("9411", m(500, 0), "9412", m(300, 0), "92", m(0, 800)));

        LigneAnalytiqueResponse r94 = ligne(etat, "94");
        assertThat(r94.rubrique()).isTrue();
        assertThat(r94.niveau()).isZero();
        assertThat(r94.mouvementDebit()).isEqualByComparingTo("800");
        assertThat(r94.soldeAnterieurDebit()).isEqualByComparingTo("100");
        assertThat(r94.soldeFinalDebiteur()).isEqualByComparingTo("900");

        LigneAnalytiqueResponse a = ligne(etat, "9411");
        assertThat(a.niveau()).isEqualTo(1);
        assertThat(a.soldeFinalDebiteur()).isEqualByComparingTo("600");

        LigneAnalytiqueResponse r92 = ligne(etat, "92");
        assertThat(r92.soldeFinalCrediteur()).isEqualByComparingTo("900");
        assertThat(r92.soldeFinalDebiteur()).isZero();

        // Compte sans mouvement présent, à zéro.
        assertThat(ligne(etat, "98").soldeFinalDebiteur()).isZero();

        // Totaux sans double comptage des rubriques.
        assertThat(etat.totalMouvementDebit()).isEqualByComparingTo("800");
        assertThat(etat.totalMouvementCredit()).isEqualByComparingTo("800");
        assertThat(etat.equilibre()).isTrue();
    }

    @Test
    void etat_desequilibreSignale() {
        EtatAnalytiqueResponse etat = ComptabiliteAnalytiqueService.construireEtat(DU, AU,
            List.of(compte(1, "94", "Comptes de coûts")), Map.of(), Map.of("94", m(50, 0)));
        assertThat(etat.equilibre()).isFalse();
    }

    @Test
    void creerSousCompte_concateneLeSuffixeEtRendLeParentNonImputable() {
        CompteOHADARepository comptes = mock(CompteOHADARepository.class);
        EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
        CompteOHADA parent = compte(2, "94", "Comptes de coûts");
        when(comptes.findByNumero("94")).thenReturn(Optional.of(parent));
        when(comptes.existsByNumero("9411")).thenReturn(false);
        when(comptes.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ecritures.existsByCompteId(2L)).thenReturn(false);

        var res = new ComptabiliteAnalytiqueService(comptes, ecritures)
            .creerSousCompte(new SousCompteAnalytiqueRequest("94", "11", "Coût atelier A"));

        assertThat(res.numero()).isEqualTo("9411");
        assertThat(res.classe()).isEqualTo(9);
        assertThat(res.manuel()).isTrue();
        assertThat(res.parentNumero()).isEqualTo("94");
        assertThat(parent.isImputable()).isFalse();
    }

    @Test
    void creerSousCompte_refuseUnParentHorsComptabiliteAnalytique() {
        var service = new ComptabiliteAnalytiqueService(mock(CompteOHADARepository.class), mock(EcritureGrandLivreRepository.class));
        assertThatThrownBy(() -> service.creerSousCompte(new SousCompteAnalytiqueRequest("9011", "1", "x")))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.creerSousCompte(new SousCompteAnalytiqueRequest("601", "1", "x")))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void creerSousCompte_refuseUnNumeroExistant() {
        CompteOHADARepository comptes = mock(CompteOHADARepository.class);
        when(comptes.findByNumero("94")).thenReturn(Optional.of(compte(2, "94", "Comptes de coûts")));
        when(comptes.existsByNumero("9411")).thenReturn(true);
        var service = new ComptabiliteAnalytiqueService(comptes, mock(EcritureGrandLivreRepository.class));
        assertThatThrownBy(() -> service.creerSousCompte(new SousCompteAnalytiqueRequest("94", "11", "x")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("déjà existant");
        verify(comptes, never()).save(any());
    }
}

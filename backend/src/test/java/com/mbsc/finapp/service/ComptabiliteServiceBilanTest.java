package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.comptabilite.BilanResponse;
import com.mbsc.finapp.dto.comptabilite.EtatFinancierLigne;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.ReleveBancaireRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bilan : actif, passif et résultat viennent des comptes de classes 1 à 8 ; les comptes de la classe 9 (engagements hors
 * bilan, comptabilité analytique), sans type, n'y figurent jamais — ils étaient rangés à l'actif.
 */
class ComptabiliteServiceBilanTest {

    private static final LocalDate AU = LocalDate.of(2026, 10, 31);

    private final EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
    private final ComptabiliteService service = new ComptabiliteService(mock(PieceComptableRepository.class), ecritures,
        mock(CompteOHADARepository.class), mock(MouvementStockRepository.class), mock(VenteRepository.class),
        mock(ReferenceGenerator.class), mock(CurrentUserProvider.class), mock(PeriodeComptableService.class),
        new ConversionDeviseService(mock(com.mbsc.finapp.repository.TauxChangeRepository.class)),
        mock(ReleveBancaireRepository.class));

    private static Object[] ligne(String numero, String libelle, TypeCompte type, Integer classe, String debit, String credit) {
        return new Object[] {numero, libelle, type, classe, new BigDecimal(debit), new BigDecimal(credit)};
    }

    /**
     * Un petit exercice en partie double : capital 1 000 versé en caisse ; achat de marchandises 300 au comptant ; vente de
     * 450 encaissée, dont le coût (100) sort du stock. Cumuls : stock 300 - 100, caisse 1 000 + 450 - 300.
     */
    private static List<Object[]> exercice() {
        return List.of(
            ligne("1013", "Capital souscrit, appelé, versé", TypeCompte.PASSIF, 1, "0", "1000"),
            ligne("3111", "Marchandises", TypeCompte.ACTIF, 3, "300", "100"),
            ligne("5711", "Caisse", TypeCompte.ACTIF, 5, "1450", "300"),
            ligne("7011", "Ventes de marchandises", TypeCompte.PRODUIT, 7, "0", "450"),
            ligne("6031", "Variation des stocks", TypeCompte.CHARGE, 6, "100", "0"));
    }

    @Test
    void le_bilan_se_construit_des_classes_1_a_8() {
        when(ecritures.cumulParCompte(AU)).thenReturn(exercice());

        BilanResponse b = service.bilan(AU);

        assertThat(b.actifs()).extracting(EtatFinancierLigne::numero).containsExactly("3111", "5711");
        assertThat(b.actifs()).extracting(EtatFinancierLigne::montant)
            .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("200"), new BigDecimal("1150"));
        assertThat(b.totalActif()).isEqualByComparingTo("1350");
        assertThat(b.passifs()).extracting(EtatFinancierLigne::numero).containsExactly("1013");
        assertThat(b.totalPassifHorsResultat()).isEqualByComparingTo("1000");
        assertThat(b.resultatNet()).isEqualByComparingTo("350");           // 450 - 100
        assertThat(b.totalPassif()).isEqualByComparingTo("1350");
        assertThat(b.equilibre()).isTrue();
    }

    @Test
    void les_comptes_de_classe_9_ne_figurent_ni_a_l_actif_ni_au_passif_et_ne_changent_aucun_total() {
        List<Object[]> avec9 = new java.util.ArrayList<>(exercice());
        // Engagement hors bilan (90x) et ecritures analytiques (92, 94, 98) : tous sans type, equilibres entre eux.
        avec9.add(ligne("9011", "Engagements donnés", null, 9, "500", "0"));
        avec9.add(ligne("9012", "Contrepartie des engagements donnés", null, 9, "0", "500"));
        avec9.add(ligne("92", "Comptes réfléchis", null, 9, "200", "0"));
        avec9.add(ligne("94.1", "Coût atelier A", null, 9, "0", "150"));
        avec9.add(ligne("98", "Comptes de résultats", null, 9, "0", "50"));
        when(ecritures.cumulParCompte(AU)).thenReturn(avec9);

        BilanResponse b = service.bilan(AU);

        assertThat(b.actifs()).extracting(EtatFinancierLigne::numero).containsExactly("3111", "5711");
        assertThat(b.passifs()).extracting(EtatFinancierLigne::numero).containsExactly("1013");
        assertThat(b.actifs()).extracting(EtatFinancierLigne::classe).doesNotContain(9);
        assertThat(b.passifs()).extracting(EtatFinancierLigne::classe).doesNotContain(9);
        assertThat(b.totalActif()).isEqualByComparingTo("1350");
        assertThat(b.totalPassif()).isEqualByComparingTo("1350");
        assertThat(b.resultatNet()).isEqualByComparingTo("350");
        assertThat(b.equilibre()).isTrue();
    }

    @Test
    void un_compte_de_classe_9_meme_dote_d_un_type_reste_hors_du_bilan() {
        List<Object[]> avec9 = new java.util.ArrayList<>(exercice());
        avec9.add(ligne("9013", "Compte de classe 9 typé par erreur", TypeCompte.ACTIF, 9, "40", "0"));
        when(ecritures.cumulParCompte(AU)).thenReturn(avec9);

        BilanResponse b = service.bilan(AU);

        assertThat(b.actifs()).extracting(EtatFinancierLigne::numero).doesNotContain("9013");
        assertThat(b.totalActif()).isEqualByComparingTo("1350");
        assertThat(b.equilibre()).isTrue();
    }
}

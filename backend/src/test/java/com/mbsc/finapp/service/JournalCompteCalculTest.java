package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.dto.comptabilite.JournalCompteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal d'un compte de tresorerie : solde cumule ligne apres ligne depuis le solde d'ouverture, entrees et sorties,
 * reference de note retrouvee dans le libelle d'une ecriture importee, conversion ligne par ligne en francs.
 */
class JournalCompteCalculTest {

    private static final LocalDate DU = LocalDate.of(2026, 10, 1);
    private static final LocalDate AU = LocalDate.of(2026, 10, 31);

    private static JournalCompteCalcul.MouvementBrut mvt(int jour, String note, String objet, String libelle,
                                                         String debit, String credit) {
        return new JournalCompteCalcul.MouvementBrut(LocalDate.of(2026, 10, jour), note, objet, "S. Ilunga", libelle,
            "6588", "PC-1", false, new BigDecimal(debit), new BigDecimal(credit));
    }

    @Test
    void le_solde_se_cumule_depuis_l_ouverture() {
        JournalCompteResponse j = JournalCompteCalcul.construire("571", "Caisse", DU, AU,
            ConversionDeviseService.Affichage.base(), new BigDecimal("689.86"), List.of(
                mvt(5, "NF-2026-000100", "Mouture 4 sacs", null, "0", "40.00"),
                mvt(5, "NF-2026-000108", "Remboursement depense", null, "409.09", "0"),
                mvt(15, "NF-2026-000124", "Fonds DG", null, "15000", "0")));

        assertThat(j.soldeOuverture()).isEqualByComparingTo("689.86");
        assertThat(j.lignes()).extracting(JournalCompteResponse.Ligne::solde)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("649.86"), new BigDecimal("1058.95"), new BigDecimal("16058.95"));
        assertThat(j.lignes().get(0).entree()).isNull();
        assertThat(j.lignes().get(0).sortie()).isEqualByComparingTo("40");
        assertThat(j.totalEntrees()).isEqualByComparingTo("15409.09");
        assertThat(j.totalSorties()).isEqualByComparingTo("40");
        assertThat(j.soldeCloture()).isEqualByComparingTo("16058.95");
    }

    @Test
    void la_note_d_une_ecriture_importee_se_lit_dans_son_libelle() {
        JournalCompteResponse j = JournalCompteCalcul.construire("571", "Caisse", DU, AU,
            ConversionDeviseService.Affichage.base(), BigDecimal.ZERO, List.of(
                mvt(5, null, null, "Reglement frais medicaux, reference piece NF-2026-000176, decaissement", "0", "255"),
                mvt(6, null, null, "Apport sans note", "10", "0")));

        assertThat(j.lignes().get(0).note()).isEqualTo("NF-2026-000176");
        assertThat(j.lignes().get(0).objet()).startsWith("Reglement frais medicaux");
        assertThat(j.lignes().get(1).note()).isNull();
    }

    @Test
    void en_francs_chaque_ligne_est_convertie_puis_cumulee() {
        var francs = new ConversionDeviseService.Affichage(Devise.CDF, new BigDecimal("2200"), AU);
        JournalCompteResponse j = JournalCompteCalcul.construire("571", "Caisse", DU, AU, francs, new BigDecimal("100"),
            List.of(mvt(5, "NF-2026-000100", "Achat", null, "0", "4.55"), mvt(6, "NF-2026-000101", "Achat", null, "0", "2.27")));

        assertThat(j.devise()).isEqualTo(Devise.CDF);
        assertThat(j.soldeOuverture()).isEqualByComparingTo("220000");
        assertThat(j.lignes()).extracting(JournalCompteResponse.Ligne::sortie)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("10010"), new BigDecimal("4994"));
        assertThat(j.soldeCloture()).isEqualByComparingTo(j.soldeOuverture().subtract(j.totalSorties()));
    }

    @Test
    void un_mouvement_nul_n_apparait_pas() {
        JournalCompteResponse j = JournalCompteCalcul.construire("571", "Caisse", DU, AU,
            ConversionDeviseService.Affichage.base(), BigDecimal.ZERO, List.of(mvt(5, null, "x", null, "0", "0")));

        assertThat(j.lignes()).isEmpty();
    }
}

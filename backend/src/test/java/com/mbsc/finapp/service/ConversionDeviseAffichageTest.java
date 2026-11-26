package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.dto.comptabilite.PieceResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Devise d'affichage d'un etat : arrondi par ligne (francs sans decimale) et totaux de piece recalcules sur les lignes converties. */
class ConversionDeviseAffichageTest {

    private static final LocalDate JOUR = LocalDate.parse("2026-10-21");

    private static ConversionDeviseService.Affichage francs(String taux) {
        return new ConversionDeviseService.Affichage(Devise.CDF, new BigDecimal(taux), JOUR);
    }

    @Test
    void la_devise_de_base_ne_change_aucun_montant() {
        ConversionDeviseService.Affichage base = ConversionDeviseService.Affichage.base();

        assertThat(base.estConvertie()).isFalse();
        assertThat(base.devise()).isEqualTo(Devise.USD);
        assertThat(base.taux()).isNull();
        BigDecimal montant = new BigDecimal("12.345");
        assertThat(base.convertir(montant)).isSameAs(montant);   // ni conversion ni arrondi
        assertThat(base.convertir(null)).isNull();
    }

    @Test
    void en_francs_chaque_montant_est_arrondi_a_l_unite_a_la_hausse_a_la_moitie() {
        ConversionDeviseService.Affichage f = francs("2810");

        assertThat(f.estConvertie()).isTrue();
        assertThat(f.convertir(new BigDecimal("0.15"))).isEqualByComparingTo("422");   // 421,50
        assertThat(f.convertir(new BigDecimal("0.14"))).isEqualByComparingTo("393");   // 393,40
        assertThat(f.convertir(new BigDecimal("100.00"))).isEqualByComparingTo("281000");
        assertThat(f.convertir(new BigDecimal("0.15")).scale()).isZero();
        assertThat(f.convertir(new BigDecimal("-0.15"))).isEqualByComparingTo("-422");   // un solde a decouvert garde son signe
        assertThat(f.convertir(null)).isNull();
    }

    @Test
    void le_taux_se_lit_en_francs_pour_un_dollar_meme_avec_des_decimales() {
        assertThat(francs("2799.5000").convertir(new BigDecimal("1.00"))).isEqualByComparingTo("2800");   // 2 799,50 -> 2 800
    }

    @Test
    void une_piece_convertie_additionne_ses_lignes_converties_et_non_son_total_converti() {
        CompteOHADA caisse = CompteOHADA.builder().numero("5711").libelle("Caisse").build();
        CompteOHADA ventes = CompteOHADA.builder().numero("7011").libelle("Ventes").build();
        PieceComptable piece = PieceComptable.builder().id(1L).reference("PC-1").datePiece(JOUR).journal(JournalComptable.CAISSE)
            .libelle("Vente").statut(StatutPiece.COMPTABILISEE).totalDebit(new BigDecimal("0.45")).totalCredit(new BigDecimal("0.45"))
            .lignes(List.of(
                ligne(1, caisse, "0.15", "0"), ligne(2, caisse, "0.15", "0"), ligne(3, caisse, "0.15", "0"),
                ligne(4, ventes, "0", "0.45")))
            .build();

        PieceResponse convertie = PieceResponse.from(piece).enDevise(francs("2810")::convertir);

        // 3 x 422 = 1 266 (et non 0,45 x 2 810 = 1 264,50 -> 1 265) : les colonnes de la piece s'additionnent.
        assertThat(convertie.totalDebit()).isEqualByComparingTo("1266");
        assertThat(convertie.totalCredit()).isEqualByComparingTo("1265");   // la ligne 7011 : 0,45 x 2 810 = 1 264,50 -> 1 265
        assertThat(convertie.lignes()).hasSize(4);
        assertThat(convertie.lignes().get(0).debit()).isEqualByComparingTo("422");
        assertThat(convertie.lignes().get(3).credit()).isEqualByComparingTo("1265");
        assertThat(convertie.reference()).isEqualTo("PC-1");
        assertThat(convertie.journal()).isEqualTo(JournalComptable.CAISSE);
        assertThat(convertie.lignes().get(0).compteNumero()).isEqualTo("5711");
    }

    @Test
    void sans_lignes_une_piece_convertit_ses_totaux_enregistres() {
        PieceComptable piece = PieceComptable.builder().id(2L).reference("PC-2").datePiece(JOUR).journal(JournalComptable.CAISSE)
            .libelle("Sans lignes").statut(StatutPiece.COMPTABILISEE).totalDebit(new BigDecimal("2.00")).totalCredit(new BigDecimal("2.00")).build();

        PieceResponse convertie = PieceResponse.from(piece, false).enDevise(francs("2800")::convertir);

        assertThat(convertie.lignes()).isEmpty();
        assertThat(convertie.totalDebit()).isEqualByComparingTo("5600");
        assertThat(convertie.totalCredit()).isEqualByComparingTo("5600");
    }

    private static EcritureGrandLivre ligne(long id, CompteOHADA compte, String debit, String credit) {
        return EcritureGrandLivre.builder().id(id).compte(compte).debit(new BigDecimal(debit)).credit(new BigDecimal(credit))
            .libelle("Ligne").dateEcriture(JOUR).build();
    }
}

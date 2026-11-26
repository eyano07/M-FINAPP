package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PeriodeRapport;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse;
import com.mbsc.finapp.service.RapportCaisseCalcul.MouvementBrut;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calcul du rapport de caisse : entrees et sorties, resume par jour et par nature, solde de cloture — en dollars (devise de
 * base) et en francs, ou chaque montant est converti puis arrondi AVANT d'etre additionne, de sorte que le rapport
 * s'additionne exactement.
 */
class RapportCaisseCalculTest {

    private static final LocalDate JOUR1 = LocalDate.parse("2026-10-19");
    private static final LocalDate JOUR2 = LocalDate.parse("2026-10-20");
    private static final LocalDate JOUR3 = LocalDate.parse("2026-10-22");
    private static final PeriodeRapport.Bornes SEMAINE = PeriodeRapport.SEMAINE.bornes(JOUR1);

    private static MouvementBrut entree(LocalDate jour, String montant, String compte, String libelleCompte) {
        return new MouvementBrut(jour, null, "PC-" + montant, null, "Entree " + montant, compte, libelleCompte, null, null, false,
            new BigDecimal(montant), BigDecimal.ZERO);
    }

    private static MouvementBrut sortie(LocalDate jour, String montant, String compte, String libelleCompte) {
        return new MouvementBrut(jour, null, "PC-" + montant, null, "Sortie " + montant, compte, libelleCompte, null, null, false,
            BigDecimal.ZERO, new BigDecimal(montant));
    }

    private static RapportCaisseResponse rapport(ConversionDeviseService.Affichage affichage, String ouverture, MouvementBrut... m) {
        return RapportCaisseCalcul.construire(PeriodeRapport.SEMAINE, JOUR1, SEMAINE, affichage, new BigDecimal(ouverture), List.of(m));
    }

    @Test
    void en_dollars_les_totaux_les_jours_et_la_cloture_s_additionnent() {
        RapportCaisseResponse r = rapport(ConversionDeviseService.Affichage.base(), "100.00",
            entree(JOUR1, "50.00", "7011", "Ventes"), sortie(JOUR1, "20.50", "6047", "Fournitures"),
            sortie(JOUR2, "10.00", "6047", "Fournitures"), entree(JOUR3, "5.25", "7011", "Ventes"));

        assertThat(r.devise()).isEqualTo(Devise.USD);
        assertThat(r.taux()).isNull();
        assertThat(r.du()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(r.au()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(r.soldeOuverture()).isEqualByComparingTo("100.00");
        assertThat(r.totalEntrees()).isEqualByComparingTo("55.25");
        assertThat(r.totalSorties()).isEqualByComparingTo("30.50");
        assertThat(r.soldeCloture()).isEqualByComparingTo("124.75");   // 100 + 55,25 - 30,50
        assertThat(r.entrees()).hasSize(2);
        assertThat(r.sorties()).hasSize(2);

        assertThat(r.jours()).extracting(RapportCaisseResponse.Jour::date).containsExactly(JOUR1, JOUR2, JOUR3);
        assertThat(r.jours()).extracting(RapportCaisseResponse.Jour::net)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("29.50"), new BigDecimal("-10.00"), new BigDecimal("5.25"));
        assertThat(r.jours()).extracting(RapportCaisseResponse.Jour::soldeCloture)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("129.50"), new BigDecimal("119.50"), new BigDecimal("124.75"));
        assertThat(r.jours().get(0).nombreEntrees()).isEqualTo(1);
        assertThat(r.jours().get(0).nombreSorties()).isEqualTo(1);
        assertThat(r.jours().get(2).soldeCloture()).isEqualByComparingTo(r.soldeCloture());
    }

    @Test
    void en_francs_chaque_montant_est_arrondi_avant_d_etre_additionne() {
        // 0,15 $ x 2 810 = 421,50 FC -> 422 FC par ligne ; trois lignes = 1 266 FC, alors que 0,45 $ x 2 810 = 1 264,50 -> 1 265.
        // Le rapport doit afficher 1 266 : la somme des lignes affichees, pas la conversion du total.
        ConversionDeviseService.Affichage francs = new ConversionDeviseService.Affichage(Devise.CDF, new BigDecimal("2810"), JOUR1);
        RapportCaisseResponse r = rapport(francs, "1.00",
            entree(JOUR1, "0.15", "7011", "Ventes"), entree(JOUR1, "0.15", "7011", "Ventes"), entree(JOUR2, "0.15", "7011", "Ventes"));

        assertThat(r.devise()).isEqualTo(Devise.CDF);
        assertThat(r.taux()).isEqualByComparingTo("2810");
        assertThat(r.entrees()).extracting(RapportCaisseResponse.Mouvement::montant)
            .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("422"), new BigDecimal("422"), new BigDecimal("422"));
        assertThat(r.totalEntrees()).isEqualByComparingTo("1266");
        assertThat(r.soldeOuverture()).isEqualByComparingTo("2810");
        assertThat(r.soldeCloture()).isEqualByComparingTo("4076");   // 2 810 + 1 266
        assertThat(r.jours().get(0).entrees()).isEqualByComparingTo("844");
        assertThat(r.jours().get(1).soldeCloture()).isEqualByComparingTo("4076");
        assertThat(r.soldeCloture().scale()).isZero();   // francs sans decimale
    }

    @Test
    void le_resume_par_nature_regroupe_par_compte_dans_l_ordre_des_numeros_et_met_les_mouvements_sans_contrepartie_en_dernier() {
        RapportCaisseResponse r = rapport(ConversionDeviseService.Affichage.base(), "0",
            sortie(JOUR1, "40.00", "6047", "Fournitures"), entree(JOUR1, "100.00", "7011", "Ventes"),
            entree(JOUR2, "7.00", null, null), sortie(JOUR2, "10.00", "6047", "Fournitures"), entree(JOUR3, "20.00", "7011", "Ventes"));

        assertThat(r.natures()).extracting(RapportCaisseResponse.Nature::compteNumero).containsExactly("6047", "7011", "");
        RapportCaisseResponse.Nature fournitures = r.natures().get(0);
        assertThat(fournitures.compteLibelle()).isEqualTo("Fournitures");
        assertThat(fournitures.sorties()).isEqualByComparingTo("50.00");
        assertThat(fournitures.entrees()).isEqualByComparingTo("0");
        assertThat(fournitures.nombre()).isEqualTo(2);
        assertThat(r.natures().get(1).entrees()).isEqualByComparingTo("120.00");
        assertThat(r.natures().get(2).entrees()).isEqualByComparingTo("7.00");
        // Chaque mouvement est dans une seule nature : les natures retombent sur les totaux.
        assertThat(r.natures().stream().map(RapportCaisseResponse.Nature::entrees).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(r.totalEntrees());
        assertThat(r.natures().stream().map(RapportCaisseResponse.Nature::sorties).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(r.totalSorties());
    }

    @Test
    void une_ligne_a_zero_n_est_ni_une_entree_ni_une_sortie_et_un_debit_et_un_credit_se_compensent() {
        MouvementBrut zero = new MouvementBrut(JOUR1, null, "PC-0", null, "Zero", "7011", "Ventes", null, null, false,
            BigDecimal.ZERO, BigDecimal.ZERO);
        MouvementBrut compense = new MouvementBrut(JOUR1, null, "PC-C", null, "Compense", "7011", "Ventes", null, null, false,
            new BigDecimal("30.00"), new BigDecimal("12.00"));
        RapportCaisseResponse r = rapport(ConversionDeviseService.Affichage.base(), "0", zero, compense);

        assertThat(r.entrees()).hasSize(1);
        assertThat(r.entrees().get(0).montant()).isEqualByComparingTo("18.00");
        assertThat(r.sorties()).isEmpty();
        assertThat(r.jours()).hasSize(1);
    }

    @Test
    void une_periode_sans_mouvement_garde_le_solde_d_ouverture_comme_cloture() {
        RapportCaisseResponse r = rapport(ConversionDeviseService.Affichage.base(), "250.00");

        assertThat(r.totalEntrees()).isEqualByComparingTo("0");
        assertThat(r.totalSorties()).isEqualByComparingTo("0");
        assertThat(r.soldeCloture()).isEqualByComparingTo("250.00");
        assertThat(r.jours()).isEmpty();
        assertThat(r.entrees()).isEmpty();
        assertThat(r.sorties()).isEmpty();
        assertThat(r.natures()).isEmpty();
    }

    @Test
    void une_caisse_a_decouvert_garde_son_signe() {
        RapportCaisseResponse r = rapport(ConversionDeviseService.Affichage.base(), "-30.00", sortie(JOUR1, "20.00", "6047", "Fournitures"));

        assertThat(r.soldeOuverture()).isEqualByComparingTo("-30.00");
        assertThat(r.soldeCloture()).isEqualByComparingTo("-50.00");
        assertThat(r.jours().get(0).soldeCloture()).isEqualByComparingTo("-50.00");
    }

    @Test
    void les_champs_de_chaque_mouvement_sont_transmis_et_une_piece_annulee_est_signalee() {
        MouvementBrut m = new MouvementBrut(JOUR2, java.time.Instant.parse("2026-10-20T09:15:00Z"), "PC-2026-000012", "REC-2026-000007",
            "Reglement note NF-2026-000009", "6047", "Fournitures de bureau", "4452", "Cyril Mwamba", true,
            BigDecimal.ZERO, new BigDecimal("116.00"));
        RapportCaisseResponse.Mouvement sortie = rapport(ConversionDeviseService.Affichage.base(), "0", m).sorties().get(0);

        assertThat(sortie.date()).isEqualTo(JOUR2);
        assertThat(sortie.heure()).isEqualTo(java.time.Instant.parse("2026-10-20T09:15:00Z"));
        assertThat(sortie.pieceReference()).isEqualTo("PC-2026-000012");
        assertThat(sortie.numeroRecu()).isEqualTo("REC-2026-000007");
        assertThat(sortie.libelle()).isEqualTo("Reglement note NF-2026-000009");
        assertThat(sortie.compteNumero()).isEqualTo("6047");
        assertThat(sortie.autresComptes()).isEqualTo("4452");
        assertThat(sortie.caissierNom()).isEqualTo("Cyril Mwamba");
        assertThat(sortie.annulee()).isTrue();
        assertThat(sortie.montant()).isEqualByComparingTo("116.00");
    }
}

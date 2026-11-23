package com.mbsc.finapp.service.widget;

import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.caisse.LigneBalanceResponse;
import com.mbsc.finapp.dto.widget.WidgetResume;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetServiceTest {

    @Test
    void chaque_role_voit_l_etape_qui_lui_revient() {
        Set<String> dfin = Set.of("DFIN");
        Set<String> da = Set.of("DA");
        Set<String> caissier = Set.of("CAISSIER");
        assertThat(WidgetService.actionPour(StatutNote.SOUMISE, SensTransaction.DECAISSEMENT, dfin)).isEqualTo("À vérifier");
        assertThat(WidgetService.actionPour(StatutNote.SOUMISE, SensTransaction.DECAISSEMENT, da)).isNull();
        assertThat(WidgetService.actionPour(StatutNote.VERIFIEE_DFIN, SensTransaction.DECAISSEMENT, da)).isEqualTo("À valider");
        assertThat(WidgetService.actionPour(StatutNote.VALIDEE_DA, SensTransaction.DECAISSEMENT, dfin)).isEqualTo("À transmettre à la caisse");
        assertThat(WidgetService.actionPour(StatutNote.TRANSMISE_CAISSE, SensTransaction.DECAISSEMENT, caissier)).isEqualTo("À payer");
        assertThat(WidgetService.actionPour(StatutNote.TRANSMISE_CAISSE, SensTransaction.ENCAISSEMENT, caissier)).isEqualTo("À encaisser");
        assertThat(WidgetService.actionPour(StatutNote.TRANSMISE_CAISSE, SensTransaction.DECAISSEMENT, dfin)).isNull();
        assertThat(WidgetService.actionPour(StatutNote.PAYEE, SensTransaction.DECAISSEMENT, Set.of("DFIN", "DA", "CAISSIER"))).isNull();
        assertThat(WidgetService.actionPour(StatutNote.BROUILLON, SensTransaction.DECAISSEMENT, Set.of("COMPTABLE"))).isNull();
    }

    @Test
    void la_tresorerie_regroupe_caisse_banques_et_mobile_money() {
        WidgetResume.Tresorerie t = WidgetService.tresorerie(List.of(
            LigneBalanceResponse.of("571", "Caisse", null, new BigDecimal("150"), new BigDecimal("50")),
            LigneBalanceResponse.of("5211", "Banque A", null, new BigDecimal("300"), BigDecimal.ZERO),
            LigneBalanceResponse.of("5212", "Banque B", null, new BigDecimal("200"), BigDecimal.ZERO),
            LigneBalanceResponse.of("5521", "M-Pesa", null, new BigDecimal("40"), BigDecimal.ZERO)),
            List.<Object[]>of(new Object[]{"571", new BigDecimal("30"), new BigDecimal("12")},
                new Object[]{"5211", new BigDecimal("5"), BigDecimal.ZERO},
                new Object[]{"585", new BigDecimal("99"), new BigDecimal("99")}));
        assertThat(t.canaux()).extracting(WidgetResume.Canal::nom).containsExactly("Caisse", "Banque", "Mobile money");
        assertThat(t.canaux().get(1).solde()).isEqualByComparingTo("500");
        assertThat(t.total()).isEqualByComparingTo("640");
        // Mouvements du jour : caisse, banques, mobile money seulement (585 n'est pas un canal de trésorerie suivi).
        assertThat(t.entreesJour()).isEqualByComparingTo("35");
        assertThat(t.sortiesJour()).isEqualByComparingTo("12");
    }

    @Test
    void sans_banque_ni_mobile_money_seule_la_caisse_apparait() {
        WidgetResume.Tresorerie t = WidgetService.tresorerie(List.of(
            LigneBalanceResponse.of("571", "Caisse", null, new BigDecimal("10"), BigDecimal.ZERO)), List.of());
        assertThat(t.canaux()).hasSize(1);
    }

    @Test
    void le_widget_tresorerie_est_reserve_aux_roles_du_mini_tableau_de_bord() {
        assertThat(WidgetService.ROLES_TRESORERIE).containsExactlyInAnyOrder("CAISSIER", "DA", "DFIN", "COMPTABLE", "DG", "ADMIN");
        assertThat(WidgetService.ROLES_RESULTAT).containsExactlyInAnyOrder("DFIN", "DA", "ADMIN");
        assertThat(MouvementsTresorerie.concerneLaTresorerie("5711")).isTrue();
        assertThat(MouvementsTresorerie.concerneLaTresorerie("6011")).isFalse();
    }

    @Test
    void le_resultat_net_est_produits_moins_charges() {
        var produit = com.mbsc.finapp.domain.enums.TypeCompte.PRODUIT;
        var charge = com.mbsc.finapp.domain.enums.TypeCompte.CHARGE;
        BigDecimal r = WidgetService.resultatNet(List.<Object[]>of(
            new Object[]{"701", "Ventes", produit, 7, new BigDecimal("10"), new BigDecimal("1000")},
            new Object[]{"601", "Achats", charge, 6, new BigDecimal("400"), new BigDecimal("50")},
            new Object[]{"571", "Caisse", null, 5, new BigDecimal("999"), BigDecimal.ZERO}));
        assertThat(r).isEqualByComparingTo("640");   // (1000 − 10) − (400 − 50)
    }

    @Test
    void l_empreinte_du_jeton_est_stable_et_ne_le_revele_pas() {
        String e = WidgetService.empreinte("abc");
        assertThat(e).hasSize(64).isEqualTo(WidgetService.empreinte("abc")).doesNotContain("abc");
    }
}

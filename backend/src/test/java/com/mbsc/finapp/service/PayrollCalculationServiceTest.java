package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.dto.drh.EntreesCalculPaie;
import com.mbsc.finapp.dto.drh.ResultatCalculPaie;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rejoue les 8 cas de l'ancien {@code PayrollServiceTest} (PayMBSC) comme
 * jeu de régression, avec les valeurs IPR recalculées pour la formule
 * complète du classeur DEBOURS MBSC 2026 (plafond 30 %, plancher 2 000 FC,
 * réduction 2 %/enfant) actée pour ce module — les résultats IPR diffèrent
 * donc délibérément de l'ancien outil, qui n'appliquait aucun de ces trois
 * mécanismes. Chaque valeur attendue est recalculée à la main dans les
 * commentaires pour rester vérifiable indépendamment du code testé.
 */
class PayrollCalculationServiceTest {

    private final PayrollCalculationService service = new PayrollCalculationService();

    private ParametresPaie parametresParDefaut() {
        return ParametresPaie.builder()
            .id(1L)
            .tauxLogement(new BigDecimal("0.30"))
            .tauxTransport(new BigDecimal("0.10"))
            .tauxCnssOuvriere(new BigDecimal("0.05"))
            .tauxCnssPatronale(new BigDecimal("0.13"))
            .tauxOnem(new BigDecimal("0.005"))
            .tauxInpp(new BigDecimal("0.03"))
            .reductionIprParEnfant(new BigDecimal("0.02"))
            .plafondEnfantsIpr(9)
            .plancherIprFc(new BigDecimal("2000"))
            .joursOuvrablesStandard(26)
            .build();
    }

    private EntreesCalculPaie entreesVides(BigDecimal salaireBaseUsd) {
        return new EntreesCalculPaie(salaireBaseUsd, new BigDecimal("100"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    // ------------------------------------------------------------------
    // Barème IPR — un cas par palier, calculé à la main dans le commentaire
    // ------------------------------------------------------------------

    @Test
    void ipr_palier1_jusqua_162000fc() {
        // baseFc = 50*2700 = 135000 (<=162000) -> 135000*0.03 = 4050
        // plafond = 135000*0.30 = 40500 (pas atteint) ; pas d'enfant ; plancher 2000 (pas atteint)
        // ipr = 4050 / 2700 = 1.50
        BigDecimal ipr = service.calculerIpr(new BigDecimal("50"), new BigDecimal("2700"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("1.50");
    }

    @Test
    void ipr_palier2_jusqua_1800000fc() {
        // baseFc = 300*2700 = 810000 -> 4860 + (810000-162000)*0.15 = 4860 + 97200 = 102060
        // plafond = 810000*0.30 = 243000 (pas atteint)
        // ipr = 102060 / 2700 = 37.80
        BigDecimal ipr = service.calculerIpr(new BigDecimal("300"), new BigDecimal("2700"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("37.80");
    }

    @Test
    void ipr_palier3_jusqua_3600000fc() {
        // baseFc = 800*2700 = 2160000 -> 250560 + (2160000-1800000)*0.30 = 250560+108000 = 358560
        // plafond = 2160000*0.30 = 648000 (pas atteint)
        // ipr = 358560 / 2700 = 132.80
        BigDecimal ipr = service.calculerIpr(new BigDecimal("800"), new BigDecimal("2700"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("132.80");
    }

    @Test
    void ipr_palier4_au_dela_de_3600000fc() {
        // baseFc = 1500*2700 = 4050000 -> 790560 + (4050000-3600000)*0.40 = 790560+180000 = 970560
        // plafond = 4050000*0.30 = 1215000 (pas atteint)
        // ipr = 970560 / 2700 = 359.4666... -> 359.47
        BigDecimal ipr = service.calculerIpr(new BigDecimal("1500"), new BigDecimal("2700"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("359.47");
    }

    @Test
    void ipr_taux_change_invalide_retourne_zero() {
        assertThat(service.calculerIpr(new BigDecimal("500"), BigDecimal.ZERO, 0, parametresParDefaut()))
            .isEqualByComparingTo("0");
        assertThat(service.calculerIpr(new BigDecimal("500"), null, 0, parametresParDefaut()))
            .isEqualByComparingTo("0");
        assertThat(service.calculerIpr(BigDecimal.ZERO, new BigDecimal("2700"), 0, parametresParDefaut()))
            .isEqualByComparingTo("0");
    }

    @Test
    void ipr_plancher_2000fc_sur_petite_base() {
        // baseFc = 1*1000 = 1000 -> 1000*0.03 = 30 (bien en dessous du plancher)
        // plafond = 1000*0.30 = 300 (30 < 300, pas de plafonnement)
        // plancher : max(30, 2000) = 2000 -> ipr = 2000/1000 = 2.00
        BigDecimal ipr = service.calculerIpr(new BigDecimal("1"), new BigDecimal("1000"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("2.00");
    }

    @Test
    void ipr_plafond_30pct_du_revenu_sur_tres_grande_base() {
        // baseFc = 10000*1000 = 10000000 (palier 4)
        // bracketRaw = 790560 + (10000000-3600000)*0.40 = 790560+2560000 = 3350560
        // plafond = 10000000*0.30 = 3000000 (< bracketRaw : le plafond s'applique)
        // ipr = 3000000 / 1000 = 3000.00
        BigDecimal ipr = service.calculerIpr(new BigDecimal("10000"), new BigDecimal("1000"), 0, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("3000.00");
    }

    @Test
    void ipr_reduction_2pct_par_enfant() {
        // Meme base que le palier 2 (bracketRaw=223560 pour baseFc=1620000, voir le test net associe) :
        // baseFc = 600*2700 = 1620000 -> 4860+(1620000-162000)*0.15 = 4860+218700 = 223560 (plafond 486000, non atteint)
        // 5 enfants -> reduction 5*2% = 10% -> 223560*0.90 = 201204
        // ipr = 201204 / 2700 = 74.52
        BigDecimal ipr = service.calculerIpr(new BigDecimal("600"), new BigDecimal("2700"), 5, parametresParDefaut());
        assertThat(ipr).isEqualByComparingTo("74.52");
    }

    @Test
    void ipr_reduction_enfants_plafonnee_a_neuf() {
        // Meme base (223560, plafond 486000). 12 enfants plafonnes a 9 -> reduction 18%.
        // 223560*0.82 = 183319.20 ; ipr = 183319.20/2700 = 67.896... -> 67.90
        ParametresPaie params = parametresParDefaut();
        BigDecimal ipr9 = service.calculerIpr(new BigDecimal("600"), new BigDecimal("2700"), 9, params);
        BigDecimal ipr12 = service.calculerIpr(new BigDecimal("600"), new BigDecimal("2700"), 12, params);
        assertThat(ipr9).isEqualByComparingTo(ipr12);
        assertThat(ipr12).isEqualByComparingTo("67.90");
    }

    // ------------------------------------------------------------------
    // Formule complète du bulletin
    // ------------------------------------------------------------------

    @Test
    void net_avec_prime_avance_et_pret() {
        // R=1000 -> I=300, J=100, H=600. gains=primeRendement=50. Q=650.
        // cnssOuvriere = 600*0.05 = 30 ; ipr(H=600, enfants=0) = 82.80 (voir calcul ci-dessous)
        //   baseFc=600*2700=1620000 -> bracketRaw=4860+(1620000-162000)*0.15=223560 (plafond 486000, non atteint)
        //   0 enfant -> ipr = 223560/2700 = 82.80
        // net = 1000 - 30 - 82.80 + 50 - 80 - 20 = 837.20
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("1000"), new BigDecimal("100"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("50"), new BigDecimal("80"), new BigDecimal("20"), 0);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));

        assertThat(r.salaireBrut()).isEqualByComparingTo("1000.00");
        assertThat(r.indemniteLogement()).isEqualByComparingTo("300.00");
        assertThat(r.indemniteTransport()).isEqualByComparingTo("100.00");
        assertThat(r.baseImposableInss()).isEqualByComparingTo("600.00");
        assertThat(r.cnssOuvriere()).isEqualByComparingTo("30.00");
        assertThat(r.ipr()).isEqualByComparingTo("82.80");
        assertThat(r.salaireNet()).isEqualByComparingTo("837.20");
    }

    @Test
    void net_sans_prime_ni_retenue_seules_cnss_et_ipr_deduites() {
        // R=500 -> I=150, J=50, H=300. gains=0. Q=300.
        // cnssOuvriere = 300*0.05 = 15
        // ipr(H=300, enfants=0) : baseFc=300*2700=810000 -> bracketRaw=4860+(810000-162000)*0.15=102060
        //   plafond=243000 (non atteint) -> ipr = 102060/2700 = 37.80
        // net = 500 - 15 - 37.80 = 447.20
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("500")), parametresParDefaut(),
            new BigDecimal("2700"));

        assertThat(r.cnssOuvriere()).isEqualByComparingTo("15.00");
        assertThat(r.ipr()).isEqualByComparingTo("37.80");
        assertThat(r.salaireNet()).isEqualByComparingTo("447.20");
        // Les charges patronales (cnssPatronale, onem, inpp) ne sont jamais deduites du net.
        assertThat(r.cnssPatronale()).isEqualByComparingTo("39.00"); // 300*0.13
        assertThat(r.onem()).isEqualByComparingTo("1.50");           // 300*0.005
        assertThat(r.inpp()).isEqualByComparingTo("9.00");           // 300*0.03 (base Q, pas H)
    }

    @Test
    void presence_50pct_proratise_le_salaire_brut() {
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("1000"), new BigDecimal("50"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));
        assertThat(r.salaireBrut()).isEqualByComparingTo("500.00");
    }

    @Test
    void presence_zero_pourcent_paie_zero_et_ne_bascule_plus_sur_cent_pourcent() {
        // Correctif explicite par rapport a l'ancien outil : presencePct=0
        // payait 100% par erreur (if (presence == 0) presence = 1.0). Ici,
        // 0% de presence doit payer 0% du salaire.
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("1000"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));
        assertThat(r.salaireBrut()).isEqualByComparingTo("0.00");
        assertThat(r.salaireNet()).isEqualByComparingTo("0.00");
    }

    @Test
    void charges_patronales_totalInss_est_la_somme_ouvriere_et_patronale() {
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("500")), parametresParDefaut(),
            new BigDecimal("2700"));
        assertThat(r.totalInss()).isEqualByComparingTo(r.cnssOuvriere().add(r.cnssPatronale()));
    }

    // ------------------------------------------------------------------
    // Base imposable IPR configurable (conformité RDC)
    // ------------------------------------------------------------------

    @Test
    void base_ipr_par_defaut_est_h_sans_deduire_la_cnss() {
        // Defaut historique (classeur DEBOURS MBSC) : la CNSS ouvriere n'est
        // PAS deduite de la base imposable. R=1000 -> H=600, base IPR = 600.
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("1000")), parametresParDefaut(),
            new BigDecimal("2700"));
        assertThat(r.baseImposableIpr()).isEqualByComparingTo("600.00");
        assertThat(r.baseImposableIpr()).isEqualByComparingTo(r.baseImposableInss());
    }

    @Test
    void base_ipr_deduit_la_cnss_quand_le_parametre_est_actif() {
        // R=1000 -> H=600 ; cnssOuvriere = 600*0.05 = 30 -> base IPR = 570.
        // baseFc = 570*2700 = 1539000 (palier 2)
        // bracketRaw = 4860 + (1539000-162000)*0.15 = 4860 + 206550 = 211410
        // plafond = 1539000*0.30 = 461700 (non atteint) ; 0 enfant ; plancher 2000 (non atteint)
        // ipr = 211410 / 2700 = 78.30  (contre 82.80 sans deduction)
        ParametresPaie params = parametresParDefaut();
        params.setCnssDeductibleIpr(true);

        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("1000")), params, new BigDecimal("2700"));

        assertThat(r.baseImposableIpr()).isEqualByComparingTo("570.00");
        assertThat(r.ipr()).isEqualByComparingTo("78.30");
        // La base CNSS elle-meme reste H : seule la base IPR change.
        assertThat(r.baseImposableInss()).isEqualByComparingTo("600.00");
    }

    // ------------------------------------------------------------------
    // Contrôle de conformité — consultatif, ne modifie aucun montant
    // ------------------------------------------------------------------

    @Test
    void conformite_aucun_avertissement_quand_tout_est_conforme() {
        // R=500 (> SMIG 207.04), 0 enfant, aucune retenue, controle transport desactive.
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("500")), parametresParDefaut(),
            new BigDecimal("2700"));
        assertThat(r.conformite().avertissements()).isEmpty();
        assertThat(r.conformite().aDesAvertissements()).isFalse();
    }

    @Test
    void conformite_signale_un_salaire_sous_le_smig() {
        // SMIG mensuel = 21500 FC/jour * 26 = 559000 FC ; /2700 = 207.04 USD.
        // R=100 est en dessous -> avertissement, mais le salaire reste inchange.
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("100")), parametresParDefaut(),
            new BigDecimal("2700"));

        assertThat(r.conformite().smigMensuelUsd()).isEqualByComparingTo("207.04");
        assertThat(r.conformite().avertissements()).anyMatch(m -> m.contains("SMIG"));
        // Consultatif : le montant paye n'est pas releve au SMIG.
        assertThat(r.salaireBrut()).isEqualByComparingTo("100.00");
    }

    @Test
    void conformite_signale_une_allocation_familiale_sous_le_minimum_legal() {
        // 1/27e du SMIG journalier = 21500/27 = 796.296296 FC par enfant et par jour.
        // 2 enfants * 26 jours = 41407.407392 FC ; /2700 = 15.34 USD.
        // Allocation saisie = 0 -> avertissement.
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("500"), new BigDecimal("100"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 2);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));

        assertThat(r.conformite().allocationFamilialeMinimumUsd()).isEqualByComparingTo("15.34");
        assertThat(r.conformite().avertissements()).anyMatch(m -> m.contains("Allocation familiale"));
    }

    @Test
    void conformite_ne_signale_rien_si_l_allocation_atteint_le_minimum() {
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("500"), new BigDecimal("100"),
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 2);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));

        assertThat(r.conformite().avertissements()).noneMatch(m -> m.contains("Allocation familiale"));
    }

    @Test
    void conformite_signale_des_retenues_au_dela_du_dixieme_du_salaire() {
        // R=1000 -> plafond = 100. Retenues = 120 + 30 = 150 > 100 -> avertissement.
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            new BigDecimal("1000"), new BigDecimal("100"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("120"), new BigDecimal("30"), 0);
        ResultatCalculPaie r = service.calculer(entrees, parametresParDefaut(), new BigDecimal("2700"));

        assertThat(r.conformite().plafondRetenuesUsd()).isEqualByComparingTo("100.00");
        assertThat(r.conformite().avertissements()).anyMatch(m -> m.contains("Retenues"));
        // Consultatif : les retenues sont bien deduites malgre l'avertissement.
        assertThat(r.salaireNet()).isEqualByComparingTo(
            new BigDecimal("1000").subtract(r.cnssOuvriere()).subtract(r.ipr()).subtract(new BigDecimal("150")));
    }

    @Test
    void conformite_controle_transport_desactive_par_defaut() {
        // plafondTransportExonereFcJour = 0 -> aucun controle, meme avec une
        // indemnite de transport elevee (R=1000 -> J=100).
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("1000")), parametresParDefaut(),
            new BigDecimal("2700"));
        assertThat(r.conformite().avertissements()).noneMatch(m -> m.contains("transport"));
    }

    @Test
    void conformite_signale_un_transport_sur_exonere_quand_le_plafond_est_renseigne() {
        // Plafond 500 FC/jour * 26 = 13000 FC ; /2700 = 4.81 USD. J=100 -> depassement.
        ParametresPaie params = parametresParDefaut();
        params.setPlafondTransportExonereFcJour(new BigDecimal("500"));

        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("1000")), params, new BigDecimal("2700"));

        assertThat(r.conformite().avertissements()).anyMatch(m -> m.contains("transport"));
    }

    @Test
    void conformite_expose_le_taux_horaire_legal_et_les_majorations() {
        // Art. 119 : 45 h/semaine -> 45*52/12 = 195 h/mois. R=1000 -> 5.13 USD/h.
        // Art. 120 : +30 % = 6.67 ; +60 % = 8.21 ; +100 % = 10.26.
        ResultatCalculPaie r = service.calculer(entreesVides(new BigDecimal("1000")), parametresParDefaut(),
            new BigDecimal("2700"));

        assertThat(r.conformite().tauxHoraireUsd()).isEqualByComparingTo("5.13");
        assertThat(r.conformite().heureSup30Usd()).isEqualByComparingTo("6.67");
        assertThat(r.conformite().heureSup60Usd()).isEqualByComparingTo("8.21");
        assertThat(r.conformite().heureSup100Usd()).isEqualByComparingTo("10.26");
    }
}

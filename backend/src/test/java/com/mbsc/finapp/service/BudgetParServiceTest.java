package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.Depassement;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.service.ImputationBudgetaire.Enveloppe;
import com.mbsc.finapp.service.SuiviBudgetaireService.Engagement;
import com.mbsc.finapp.service.SuiviBudgetaireService.ExecutionLignes;
import com.mbsc.finapp.service.SuiviBudgetaireService.Mouvement;
import com.mbsc.finapp.service.SuiviBudgetaireService.MouvementsExercice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Budget organise par service : enveloppe de chaque service, lignes generales (enveloppe commune), budget consolide,
 * depenses non budgetees d'un service, depenses non rattachees, et seuls les mois de la periode comptent.
 */
class BudgetParServiceTest {

    static final ServiceBudgetaire RESTAURANT = ServiceBudgetaire.builder().id(1L).nom("Restaurant").responsable("Jean Mbuyi").build();
    static final ServiceBudgetaire LOGISTIQUE = ServiceBudgetaire.builder().id(2L).nom("Logistique").responsable("Marie Kasongo").build();
    static final ServiceBudgetaire DIRECTION = ServiceBudgetaire.builder().id(3L).nom("Direction").responsable("Paul Ilunga").build();

    static CompteOHADA compte(String numero, TypeCompte type) {
        return CompteOHADA.builder().numero(numero).libelle("Compte " + numero).type(type).classe(numero.charAt(0) - '0').build();
    }

    static LigneBudget ligne(long id, String numero, TypeCompte type, ServiceBudgetaire service, int mois, String montant) {
        Map<Integer, BigDecimal> m = new TreeMap<>();
        for (int i = 1; i <= 12; i++) {
            m.put(i, BigDecimal.ZERO);
        }
        m.put(mois, new BigDecimal(montant));
        return LigneBudget.builder().id(id).compte(compte(numero, type)).service(service).mois(m).montantPrevu(new BigDecimal(montant)).build();
    }

    /** Budget de decembre 2026 : Restaurant (6011 : 1000), Logistique (6042 : 500), ligne generale 6052 (200), recette 7011. */
    static Budget budgetDecembre() {
        Budget b = Budget.builder().id(10L).reference("BUD-2026-000010").intitule("Décembre").exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).statut(StatutBudget.EN_EXECUTION).build();
        b.addLigne(ligne(101, "6011", TypeCompte.CHARGE, RESTAURANT, 12, "1000"));
        b.addLigne(ligne(102, "6042", TypeCompte.CHARGE, LOGISTIQUE, 12, "500"));
        b.addLigne(ligne(103, "6052", TypeCompte.CHARGE, null, 12, "200"));
        b.addLigne(ligne(104, "7011", TypeCompte.PRODUIT, null, 12, "3000"));
        return b;
    }

    static Mouvement depense(String compte, int mois, Long service, String montant) {
        return new Mouvement(compte, TypeCompte.CHARGE, mois, service, new BigDecimal(montant), BigDecimal.ZERO);
    }

    private final SuiviBudgetaireService suivi = new SuiviBudgetaireService(mock(EcritureGrandLivreRepository.class),
        mock(NoteFraisRepository.class), mock(CompteOHADARepository.class), mock(ConversionDeviseService.class),
        mock(ServiceBudgetaireRepository.class));

    private static BigDecimal total(BigDecimal[] serie) {
        return MoteurBudgetaire.total(serie);
    }

    // ---------------------------------------------------------------------
    // Imputation
    // ---------------------------------------------------------------------

    @Test
    void une_depense_s_impute_sur_le_service_puis_sur_les_lignes_generales() {
        ImputationBudgetaire imp = ImputationBudgetaire.de(budgetDecembre());
        assertThat(imp.parService()).isTrue();
        assertThat(imp.aDesLignesGenerales()).isTrue();
        // Le service a une ligne couvrant le compte (6011 couvre 6011.2).
        assertThat(imp.enveloppe("6011.2", 1L)).isEqualTo(new Enveloppe(1L, "6011"));
        // Le service n'a pas de ligne, la ligne generale couvre : enveloppe commune.
        assertThat(imp.enveloppe("6052", 1L)).isEqualTo(new Enveloppe(null, "6052"));
        // Seul un autre service a budgete ce compte : aucune enveloppe pour ce service.
        assertThat(imp.enveloppe("6042", 1L)).isNull();
        // Sans service : lignes generales seulement.
        assertThat(imp.enveloppe("6052", null)).isEqualTo(new Enveloppe(null, "6052"));
        assertThat(imp.enveloppe("6011", null)).isNull();
        // Le budget consolide couvre tous les comptes budgetes, quel que soit le service.
        assertThat(imp.ligneConsolidee("6042.1")).isEqualTo("6042");
        assertThat(imp.ligneConsolidee("6288")).isNull();
    }

    @Test
    void un_meme_compte_dans_deux_services_n_est_pas_un_recouvrement_mais_un_sous_compte_l_est() {
        assertThat(ImputationBudgetaire.chevauchements(List.of("6011", "6011", "6042"))).isEmpty();
        List<String[]> conflits = ImputationBudgetaire.chevauchements(List.of("605", "6052", "6011"));
        assertThat(conflits).hasSize(1);
        assertThat(conflits.get(0)).containsExactly("605", "6052");
    }

    // ---------------------------------------------------------------------
    // Execution sur les lignes
    // ---------------------------------------------------------------------

    @Test
    void execution_consolidee_et_par_enveloppe() {
        Budget b = budgetDecembre();
        List<Mouvement> mvts = new ArrayList<>(List.of(
            depense("6011", 12, 1L, "800"),       // Restaurant, sur sa ligne
            depense("6011", 12, null, "50"),      // sans service, compte budgete seulement par le Restaurant : non rattache
            depense("6052", 12, 1L, "120"),       // Restaurant, sur la ligne generale (pas de ligne a lui)
            depense("6042", 12, 1L, "70"),        // Restaurant, compte budgete seulement par la Logistique : hors budget du service
            depense("6042", 12, 2L, "300"),       // Logistique, sur sa ligne
            depense("6288", 12, 2L, "40"),        // Logistique, compte non budgete du tout
            depense("6011", 11, 1L, "999"),       // novembre : hors de la periode, ignore
            new Mouvement("7011", TypeCompte.PRODUIT, 12, null, BigDecimal.ZERO, new BigDecimal("2500"))));
        List<Engagement> engagements = List.of(
            new Engagement(1L, "6011", 12, new BigDecimal("400"), 1L),   // Restaurant : 800 + 400 > 1000 -> depassement 200
            new Engagement(2L, "6011", 10, new BigDecimal("777"), 1L),   // octobre : hors periode
            new Engagement(3L, "6288", 12, new BigDecimal("25"), 3L));    // Direction : non budgete
        ExecutionLignes exec = suivi.repartirSurLignes(b, new MouvementsExercice(mvts, Map.of()), engagements);

        // Budget consolide : toutes les ecritures de decembre sur les comptes budgetes.
        assertThat(total(exec.realise().get("6011"))).isEqualByComparingTo("850");
        assertThat(total(exec.realise().get("6042"))).isEqualByComparingTo("370");
        assertThat(total(exec.realise().get("6052"))).isEqualByComparingTo("120");
        assertThat(total(exec.realise().get("7011"))).isEqualByComparingTo("2500");
        assertThat(total(exec.engage().get("6011"))).isEqualByComparingTo("400");
        assertThat(exec.horsBudget()).containsOnlyKeys("6288");
        assertThat(total(exec.horsBudget().get("6288"))).isEqualByComparingTo("40");
        assertThat(total(exec.engageHorsBudget().get("6288"))).isEqualByComparingTo("25");

        // Enveloppes.
        assertThat(total(exec.realiseEnveloppe().get(new Enveloppe(1L, "6011")))).isEqualByComparingTo("800");
        assertThat(total(exec.engageEnveloppe().get(new Enveloppe(1L, "6011")))).isEqualByComparingTo("400");
        assertThat(total(exec.realiseEnveloppe().get(new Enveloppe(2L, "6042")))).isEqualByComparingTo("300");
        assertThat(total(exec.realiseEnveloppe().get(new Enveloppe(null, "6052")))).isEqualByComparingTo("120");
        assertThat(total(exec.realiseEnveloppe().get(new Enveloppe(null, "7011")))).isEqualByComparingTo("2500");
        assertThat(total(exec.imputeGeneral().get(1L))).isEqualByComparingTo("120");

        // Hors budget des services, non rattache.
        assertThat(total(exec.horsBudgetService().get(1L).get("6042"))).isEqualByComparingTo("70");
        assertThat(total(exec.horsBudgetService().get(2L).get("6288"))).isEqualByComparingTo("40");
        assertThat(total(exec.engageHorsBudgetService().get(3L).get("6288"))).isEqualByComparingTo("25");
        assertThat(exec.nonRattache()).containsOnlyKeys("6011");
        assertThat(total(exec.nonRattache().get("6011"))).isEqualByComparingTo("50");
        assertThat(exec.mouvementsSurLaPeriode()).isTrue();
    }

    @Test
    void un_budget_annuel_sans_service_se_comporte_comme_avant() {
        Budget b = Budget.builder().id(11L).reference("BUD-2026-000011").intitule("Annuel").exercice(2026).build();
        b.addLigne(ligne(201, "6011", TypeCompte.CHARGE, null, 3, "1000"));
        List<Mouvement> mvts = List.of(depense("6011", 3, 1L, "300"), depense("6011", 11, null, "200"));
        ExecutionLignes exec = suivi.repartirSurLignes(b, new MouvementsExercice(mvts, Map.of()), List.of());
        assertThat(total(exec.realise().get("6011"))).isEqualByComparingTo("500");
        // Une depense d'un service sans ligne a lui s'impute sur la ligne generale : elle consomme le meme budget.
        assertThat(total(exec.realiseEnveloppe().get(new Enveloppe(null, "6011")))).isEqualByComparingTo("500");
        assertThat(exec.horsBudgetService()).isEmpty();
        assertThat(exec.nonRattache()).isEmpty();
        assertThat(exec.imputation().parService()).isFalse();
    }

    @Test
    void aucune_ecriture_sur_la_periode() {
        Budget b = budgetDecembre();
        ExecutionLignes exec = suivi.repartirSurLignes(b, new MouvementsExercice(List.of(depense("6011", 5, 1L, "10")), Map.of()), List.of());
        assertThat(exec.mouvementsSurLaPeriode()).isFalse();
        assertThat(total(exec.realise().get("6011"))).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------------
    // Depassements
    // ---------------------------------------------------------------------

    @Test
    void depassements_des_services_puis_du_budget_consolide() {
        Budget b = budgetDecembre();
        List<Mouvement> mvts = List.of(
            depense("6011", 12, 1L, "800"),
            depense("6011", 12, null, "50"),
            depense("6042", 12, 1L, "70"),
            depense("6042", 12, 2L, "300"));
        List<Engagement> engagements = List.of(new Engagement(1L, "6011", 12, new BigDecimal("400"), 1L));
        var synthese = suivi.synthese(b, new MouvementsExercice(mvts, Map.of()), engagements,
            Map.of(1L, RESTAURANT, 2L, LOGISTIQUE, 3L, DIRECTION), true);

        // Consolide : 6011 = 850 realises + 400 engages pour 1000 prevus -> 250 de depassement ; 6042 = 370 pour 500 : rien.
        assertThat(synthese.nombreDepassements()).isEqualTo(1);
        assertThat(synthese.montantDepassement()).isEqualByComparingTo("250");
        // Restaurant : 800 + 400 - 1000 = 200 de depassement, plus 70 non budgetes sur 6042 ; Logistique dans son enveloppe.
        assertThat(synthese.servicesEnDepassement()).containsExactly("Restaurant");
        assertThat(synthese.realiseCharges()).isEqualByComparingTo("1220");
    }

    @Test
    void le_bloc_des_lignes_generales_ne_suit_que_les_depenses() {
        // Budget de decembre : lignes generales 6052 (depense) et 7011 (recette) -> bloc avec la seule depense.
        var suiviDecembre = suivi.suivre(budgetDecembre());
        assertThat(suiviDecembre.services()).extracting(s -> s.nom())
            .containsExactly("Logistique", "Restaurant", SuiviBudgetaireService.LIGNES_GENERALES);
        var generales = suiviDecembre.services().get(2);
        assertThat(generales.lignes()).extracting(l -> l.compteNumero()).containsExactly("6052");
        assertThat(generales.prevu()).isEqualByComparingTo("200");
        // Lignes generales qui ne portent qu'une recette : pas de bloc « lignes generales ».
        Budget recettesSeules = Budget.builder().id(12L).reference("BUD-2026-000012").intitule("R").exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).build();
        recettesSeules.addLigne(ligne(121, "6011", TypeCompte.CHARGE, RESTAURANT, 12, "100"));
        recettesSeules.addLigne(ligne(122, "7011", TypeCompte.PRODUIT, null, 12, "500"));
        assertThat(suivi.suivre(recettesSeules).services()).extracting(s -> s.nom()).containsExactly("Restaurant");
    }

    @Test
    void liste_des_depassements_triee_par_niveau_puis_par_montant() {
        var consolidee = new com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi(1L, "6011", "Achats", "CHARGES", "60", null,
            List.of(), List.of(), List.of(), new BigDecimal("1000"), new BigDecimal("850"), new BigDecimal("400"),
            new BigDecimal("-250"), new BigDecimal("-150"), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of("Restaurant"),
            new BigDecimal("250"));
        var ligneService = new com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi(1L, "6011", "Achats", "CHARGES", "60", null,
            List.of(), List.of(), List.of(), new BigDecimal("1000"), new BigDecimal("800"), new BigDecimal("400"),
            new BigDecimal("-200"), new BigDecimal("-200"), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of("Restaurant"),
            new BigDecimal("200"));
        var hors = new com.mbsc.finapp.dto.budget.SuiviBudgetResponse.CompteHorsBudget("6042", "Carburant", "CHARGES", List.of(),
            new BigDecimal("70"), new BigDecimal("500"));
        var service = new com.mbsc.finapp.dto.budget.SuiviBudgetResponse.SuiviService(1L, "Restaurant", "Jean Mbuyi", true,
            List.of(ligneService), new BigDecimal("1000"), new BigDecimal("800"), new BigDecimal("400"), new BigDecimal("-200"), null,
            new BigDecimal("570"), new BigDecimal("770"), 2, BigDecimal.ZERO, List.of(hors), List.of());

        List<Depassement> liste = SuiviBudgetaireService.depassements(true, List.of(consolidee), List.of(service));
        assertThat(liste).extracting(Depassement::niveau, Depassement::nature, Depassement::compteNumero)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("SERVICE", "NON_BUDGETE", "6042"),
                org.assertj.core.groups.Tuple.tuple("SERVICE", "DEPASSEMENT", "6011"),
                org.assertj.core.groups.Tuple.tuple("CONSOLIDE", "DEPASSEMENT", "6011"));
        assertThat(liste.get(0).montant()).isEqualByComparingTo("570");

        // Budget non reparti par service : seuls les depassements du budget consolide.
        assertThat(SuiviBudgetaireService.depassements(false, List.of(consolidee), List.of(service)))
            .extracting(Depassement::niveau).containsExactly("CONSOLIDE");
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.TypeBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.service.SuiviBudgetaireService.Engagement;
import com.mbsc.finapp.service.SuiviBudgetaireService.ExecutionLignes;
import com.mbsc.finapp.service.SuiviBudgetaireService.Mouvement;
import com.mbsc.finapp.service.SuiviBudgetaireService.MouvementsExercice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.mbsc.finapp.service.BudgetParServiceTest.ligne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Budgets secondaires (V162) : le budget principal ne compte que les dépenses sans budget secondaire ; un budget
 * secondaire (projet), seulement les siennes, y compris celles des notes rattachées à sa version initiale.
 */
class BudgetSecondaireTest {

    private final SuiviBudgetaireService suivi = new SuiviBudgetaireService(mock(EcritureGrandLivreRepository.class),
        mock(NoteFraisRepository.class), mock(CompteOHADARepository.class), mock(ConversionDeviseService.class),
        mock(ServiceBudgetaireRepository.class));

    static Budget budget(long id, TypeBudget type, Budget revisionDe) {
        Budget b = Budget.builder().id(id).reference("BUD-2026-0000" + id).intitule("Budget " + id).exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).statut(StatutBudget.EN_EXECUTION).typeBudget(type).revisionDe(revisionDe).build();
        b.addLigne(ligne(id * 10, "6011", TypeCompte.CHARGE, null, 12, "1000"));
        return b;
    }

    static Mouvement depense(String montant, Long budgetSecondaire) {
        return new Mouvement("6011", TypeCompte.CHARGE, 12, null, new BigDecimal(montant), BigDecimal.ZERO, budgetSecondaire);
    }

    private final MouvementsExercice mouvements = new MouvementsExercice(List.of(
        depense("300", null),    // budget principal
        depense("200", 20L),     // projet A (version initiale 20)
        depense("50", 30L)),     // projet B
        Map.of());
    private final List<Engagement> engagements = List.of(
        new Engagement(1L, "6011", 12, new BigDecimal("40"), null, null),
        new Engagement(2L, "6011", 12, new BigDecimal("15"), null, 20L));

    @Test
    void le_budget_principal_exclut_les_depenses_des_budgets_secondaires() {
        ExecutionLignes exec = suivi.repartirSurLignes(budget(10, TypeBudget.PRINCIPAL, null), mouvements, engagements);
        assertThat(MoteurBudgetaire.total(exec.realise().get("6011"))).isEqualByComparingTo("300");
        assertThat(MoteurBudgetaire.total(exec.engage().get("6011"))).isEqualByComparingTo("40");
    }

    @Test
    void un_budget_secondaire_ne_compte_que_ses_depenses() {
        ExecutionLignes exec = suivi.repartirSurLignes(budget(20, TypeBudget.SECONDAIRE, null), mouvements, engagements);
        assertThat(MoteurBudgetaire.total(exec.realise().get("6011"))).isEqualByComparingTo("200");
        assertThat(MoteurBudgetaire.total(exec.engage().get("6011"))).isEqualByComparingTo("15");
    }

    @Test
    void la_revision_d_un_budget_secondaire_retrouve_les_notes_de_sa_version_initiale() {
        Budget revision = budget(21, TypeBudget.SECONDAIRE, budget(20, TypeBudget.SECONDAIRE, null));
        assertThat(revision.racineId()).isEqualTo(20L);
        ExecutionLignes exec = suivi.repartirSurLignes(revision, mouvements, engagements);
        assertThat(MoteurBudgetaire.total(exec.realise().get("6011"))).isEqualByComparingTo("200");
    }
}

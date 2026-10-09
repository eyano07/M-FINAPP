package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.dto.budget.ControleBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Module Budget désactivé : aucune dépense n'est contrôlée ni rattachée au budget. */
class ControleBudgetaireDesactiveTest {

    @Test
    void depenseNonConcerneeSansBudgetNiJustification() {
        BudgetRepository budgets = mock(BudgetRepository.class);
        ModuleConfigService modules = mock(ModuleConfigService.class);
        when(modules.estActif(ModuleMetier.BUDGET)).thenReturn(false);
        ControleBudgetaireService service = new ControleBudgetaireService(budgets, mock(CompteOHADARepository.class),
            mock(SuiviBudgetaireService.class), mock(ConversionDeviseService.class), modules);

        ControleBudgetaireResponse r = service.controler(new ControleBudgetaireRequest(Devise.USD,
            List.of(new ControleBudgetaireRequest.Depense("6058", new BigDecimal("500"), null, false)), null));

        assertThat(r.budgetActif()).isFalse();
        assertThat(r.statut()).isEqualTo(StatutControleBudget.NON_CONCERNE);
        assertThat(r.justificationRequise()).isFalse();
        assertThat(r.budgetId()).isNull();
        assertThat(r.lignes()).allMatch(l -> l.statut() == StatutControleBudget.NON_CONCERNE);
        verifyNoInteractions(budgets);
    }
}

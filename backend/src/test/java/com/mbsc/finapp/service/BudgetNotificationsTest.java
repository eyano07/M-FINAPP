package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notifications du circuit d'un budget : le DA est prevenu d'une soumission, le DFIN (et l'auteur du budget) d'une
 * approbation ou d'un rejet, le DA et le DG d'une mise en execution.
 */
class BudgetNotificationsTest {

    private BudgetRepository budgets;
    private CurrentUserProvider courant;
    private NotificationService notifications;
    private BudgetService service;

    private final User dfin = User.builder().id(7L).prenom("Brino").nom("Kalunga").email("dfin_bruno").actif(true).build();
    private final User da = User.builder().id(8L).prenom("Ray").nom("Kalala").email("da@mbsc.cd").actif(true).build();

    @BeforeEach
    void preparer() {
        budgets = mock(BudgetRepository.class);
        courant = mock(CurrentUserProvider.class);
        notifications = mock(NotificationService.class);
        service = new BudgetService(budgets, mock(CompteOHADARepository.class), mock(ReferenceGenerator.class), courant,
            mock(SuiviBudgetaireService.class), mock(ServiceBudgetaireRepository.class), notifications);
    }

    private Budget budget(StatutBudget statut) {
        Budget b = Budget.builder().id(10L).reference("BUD-2026-000010").intitule("Budget de décembre").exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).statut(statut).elaborePar(dfin).build();
        b.addLigne(BudgetParServiceTest.ligne(101, "6011", TypeCompte.CHARGE, BudgetParServiceTest.RESTAURANT, 12, "1500"));
        b.addLigne(BudgetParServiceTest.ligne(102, "7011", TypeCompte.PRODUIT, null, 12, "4000"));
        when(budgets.findWithLignesById(10L)).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void la_soumission_previent_le_da() {
        when(courant.requireUser()).thenReturn(dfin);
        Budget b = budget(StatutBudget.BROUILLON);

        service.soumettre(10L);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifierBudget(eq(List.of(RoleType.DA)), isNull(), same(dfin), eq(TypeNotification.BUDGET_SOUMIS),
            eq("Budget à approuver"), message.capture(), same(b));
        assertThat(message.getValue()).isEqualTo("BUD-2026-000010 — Budget de décembre (Décembre 2026) : 1 500,00 USD de dépenses "
            + "prévues, 4 000,00 USD de recettes, réparti par service. Soumis par Brino Kalunga.");
    }

    @Test
    void une_revision_soumise_le_dit() {
        when(courant.requireUser()).thenReturn(dfin);
        Budget b = budget(StatutBudget.BROUILLON);
        b.setRevisionDe(Budget.builder().id(9L).reference("BUD-2026-000009").build());
        b.setNumeroRevision(1);

        service.soumettre(10L);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifierBudget(any(), any(), any(), eq(TypeNotification.BUDGET_SOUMIS), any(), message.capture(), any());
        assertThat(message.getValue()).startsWith("BUD-2026-000010 — Budget de décembre (Décembre 2026), révision n° 1 de BUD-2026-000009 :");
    }

    @Test
    void l_approbation_previent_le_dfin_et_l_auteur_du_budget() {
        when(courant.requireUser()).thenReturn(da);
        Budget b = budget(StatutBudget.SOUMIS);

        service.approuver(10L, new ActionWorkflowRequest("Conforme au plan d'action"));

        verify(notifications).notifierBudget(eq(List.of(RoleType.DFIN)), same(dfin), same(da), eq(TypeNotification.BUDGET_APPROUVE),
            eq("Budget approuvé"), eq("BUD-2026-000010 — Budget de décembre (Décembre 2026) approuvé par Ray Kalala : il peut être mis "
                + "en exécution. Observation : Conforme au plan d'action"), same(b));
    }

    @Test
    void le_rejet_previent_le_dfin_avec_le_motif() {
        when(courant.requireUser()).thenReturn(da);
        Budget b = budget(StatutBudget.SOUMIS);

        service.rejeter(10L, new ActionWorkflowRequest("Réduire le carburant de 20 %"));

        verify(notifications).notifierBudget(eq(List.of(RoleType.DFIN)), same(dfin), same(da), eq(TypeNotification.BUDGET_REJETE),
            eq("Budget rejeté"), eq("BUD-2026-000010 — Budget de décembre (Décembre 2026) rejeté par Ray Kalala : Réduire le carburant de 20 %"),
            same(b));
    }

    @Test
    void la_mise_en_execution_previent_le_da_et_le_dg() {
        when(courant.requireUser()).thenReturn(dfin);
        Budget b = budget(StatutBudget.APPROUVE);
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of());

        service.demarrerExecution(10L);

        verify(notifications).notifierBudget(eq(List.of(RoleType.DA, RoleType.DG)), isNull(), same(dfin),
            eq(TypeNotification.BUDGET_EN_EXECUTION), eq("Budget en exécution"),
            eq("BUD-2026-000010 — Budget de décembre (Décembre 2026) est en exécution : il contrôle désormais les notes de frais "
                + "de « Décembre 2026 »."), same(b));
    }

    @Test
    void la_revision_mise_en_execution_dit_quel_budget_elle_remplace() {
        when(courant.requireUser()).thenReturn(dfin);
        Budget original = Budget.builder().id(9L).reference("BUD-2026-000009").intitule("Décembre").exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).statut(StatutBudget.EN_EXECUTION).build();
        Budget b = budget(StatutBudget.APPROUVE);
        b.setRevisionDe(original);
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of(original));

        service.demarrerExecution(10L);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifierBudget(any(), any(), any(), eq(TypeNotification.BUDGET_EN_EXECUTION), any(), message.capture(), any());
        assertThat(message.getValue()).endsWith("et remplace le budget BUD-2026-000009.");
    }

    @Test
    void une_action_refusee_ne_notifie_personne() {
        when(courant.requireUser()).thenReturn(da);
        budget(StatutBudget.BROUILLON);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.approuver(10L, null))
            .isInstanceOf(com.mbsc.finapp.exception.TransitionInvalideException.class);

        verify(notifications, never()).notifierBudget(any(), any(), any(), any(), any(), any(), any());
    }
}

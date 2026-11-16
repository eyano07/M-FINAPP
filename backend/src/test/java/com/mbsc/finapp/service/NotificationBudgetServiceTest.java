package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.Notification;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.repository.NotificationRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Envoi des notifications d'un budget : une seule par personne meme si elle a plusieurs des roles vises, jamais a
 * l'auteur de l'action, lien vers le budget, titre et message ramenes a la taille de leurs colonnes.
 */
class NotificationBudgetServiceTest {

    private NotificationRepository repository;
    private UserRepository users;
    private MailNotificationService mail;
    private NotificationService service;

    private final User da = User.builder().id(1L).prenom("Ray").nom("Kalala").email("da@mbsc.cd").actif(true).build();
    private final User daEtDg = User.builder().id(2L).prenom("Paul").nom("Ilunga").email("dg@mbsc.cd").actif(true).build();
    private final User dfin = User.builder().id(3L).prenom("Brino").nom("Kalunga").email("dfin_bruno").actif(true).build();
    private final User admin = User.builder().id(4L).prenom("Pascal").nom("Tshingila").email("admin@mbsc.cd").actif(true).build();

    @BeforeEach
    void preparer() {
        repository = mock(NotificationRepository.class);
        users = mock(UserRepository.class);
        mail = mock(MailNotificationService.class);
        when(repository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        service = new NotificationService(repository, users, mock(CurrentUserProvider.class), mail);
    }

    private List<Notification> enregistrees(int nombre) {
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(nombre)).save(n.capture());
        return n.getAllValues();
    }

    @Test
    void une_seule_notification_par_personne_et_jamais_a_l_auteur_de_l_action() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DA)).thenReturn(List.of(da, daEtDg));
        when(users.findByRoles_NomAndActifTrue(RoleType.DG)).thenReturn(List.of(daEtDg, dfin));
        Budget budget = Budget.builder().id(10L).reference("BUD-2026-000010").build();

        service.notifierBudget(List.of(RoleType.DA, RoleType.DG), admin, dfin, TypeNotification.BUDGET_EN_EXECUTION,
            "Budget en exécution", "BUD-2026-000010 est en exécution.", budget);

        List<Notification> n = enregistrees(3);
        assertThat(n).extracting(x -> x.getDestinataire().getId()).containsExactly(1L, 2L, 4L);
        assertThat(n).allSatisfy(x -> {
            assertThat(x.getLien()).isEqualTo("/budgets/10");
            assertThat(x.getBudget()).isSameAs(budget);
            assertThat(x.getType()).isEqualTo(TypeNotification.BUDGET_EN_EXECUTION);
            assertThat(x.isLue()).isFalse();
        });
        verify(mail, times(3)).envoyerApresCommit(any(Notification.class));
    }

    @Test
    void sans_budget_le_lien_mene_a_la_liste_des_budgets() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DFIN)).thenReturn(List.of(dfin));

        service.notifierBudget(List.of(RoleType.DFIN), null, da, TypeNotification.BUDGET_DEPASSE, "Dépense validée sans budget",
            "NF-2026-000004 — Achat validée sans budget.", null);

        assertThat(enregistrees(1).get(0).getLien()).isEqualTo("/budgets");
    }

    @Test
    void un_auteur_inactif_n_est_pas_prevenu() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DFIN)).thenReturn(List.of());
        User parti = User.builder().id(9L).prenom("Ancien").nom("Agent").email("x").actif(false).build();

        service.notifierBudget(List.of(RoleType.DFIN), parti, da, TypeNotification.BUDGET_APPROUVE, "Budget approuvé", "…",
            Budget.builder().id(1L).build());

        verify(repository, times(0)).save(any());
    }

    @Test
    void un_motif_de_rejet_tres_long_est_tronque_au_lieu_de_faire_echouer_le_rejet() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DFIN)).thenReturn(List.of(dfin));
        String motif = "Réduire les dépenses. ".repeat(60);

        service.notifierBudget(List.of(RoleType.DFIN), null, da, TypeNotification.BUDGET_REJETE, "Budget rejeté",
            "BUD-2026-000010 rejeté : " + motif, Budget.builder().id(10L).build());

        Notification n = enregistrees(1).get(0);
        assertThat(n.getMessage()).hasSizeLessThanOrEqualTo(500).endsWith("…").startsWith("BUD-2026-000010 rejeté : Réduire");
        assertThat(NotificationService.tronquer("court", 500)).isEqualTo("court");
        assertThat(NotificationService.tronquer(null, 10)).isEmpty();
    }
}

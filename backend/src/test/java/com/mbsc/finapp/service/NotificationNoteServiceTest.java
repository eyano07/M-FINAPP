package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.NoteFrais;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notifications d'une note : une seule par personne, jamais a l'auteur de l'action ; les destinataires « autres » (le
 * responsable bar ou cuisine, qui ne voit que ses propres notes) recoivent le lien de leur role, sauf s'ils sont deja
 * notifies — avec le lien de la note — par les roles vises ou comme emetteur.
 */
class NotificationNoteServiceTest {

    private NotificationRepository repository;
    private UserRepository users;
    private NotificationService service;

    private final User dfin = User.builder().id(1L).prenom("Bruno").nom("Kalunga").email("dfin").actif(true).build();
    private final User dfinEtCuisine = User.builder().id(2L).prenom("Anne").nom("Mbuyi").email("anne").actif(true).build();
    private final User emetteurBar = User.builder().id(3L).prenom("Rita").nom("Kabila").email("bar1").actif(true).build();
    private final User bar = User.builder().id(4L).prenom("Joel").nom("Banza").email("bar2").actif(true).build();
    private final User cuisine = User.builder().id(5L).prenom("Marie").nom("Ngoy").email("cuisine").actif(true).build();
    private final User caissierEtBar = User.builder().id(6L).prenom("Cyril").nom("Mwamba").email("caisse").actif(true).build();

    @BeforeEach
    void preparer() {
        repository = mock(NotificationRepository.class);
        users = mock(UserRepository.class);
        when(repository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        service = new NotificationService(repository, users, mock(CurrentUserProvider.class), mock(MailNotificationService.class));
    }

    @Test
    void les_responsables_recoivent_le_lien_de_leur_stock_sauf_s_ils_recoivent_deja_celui_de_la_note() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DFIN)).thenReturn(List.of(dfin, dfinEtCuisine));
        when(users.findByRoles_NomAndActifTrue(RoleType.RESP_BAR)).thenReturn(List.of(emetteurBar, bar, caissierEtBar));
        when(users.findByRoles_NomAndActifTrue(RoleType.RESP_CUISINE)).thenReturn(List.of(dfinEtCuisine, cuisine));
        NoteFrais note = NoteFrais.builder().id(7L).reference("NF-2026-000007").build();
        Map<RoleType, String> responsables = new LinkedHashMap<>();
        responsables.put(RoleType.RESP_BAR, "/restaurant/stock");
        responsables.put(RoleType.RESP_CUISINE, "/restaurant/provisions");

        service.notifierNote(List.of(RoleType.DFIN), emetteurBar, caissierEtBar, TypeNotification.RETOUR_FONDS_VALIDE,
            "Retour de fonds reçu", "NF-2026-000007 : 95,20 USD rendus en caisse.", note, responsables);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(5)).save(n.capture());
        assertThat(n.getAllValues()).extracting(x -> x.getDestinataire().getId(), Notification::getLien).containsExactly(
            org.assertj.core.groups.Tuple.tuple(1L, "/notes-frais/7"),
            org.assertj.core.groups.Tuple.tuple(2L, "/notes-frais/7"),
            org.assertj.core.groups.Tuple.tuple(3L, "/notes-frais/7"),
            org.assertj.core.groups.Tuple.tuple(4L, "/restaurant/stock"),
            org.assertj.core.groups.Tuple.tuple(5L, "/restaurant/provisions"));
        assertThat(n.getAllValues()).allSatisfy(x -> {
            assertThat(x.getNoteFrais()).isSameAs(note);
            assertThat(x.getType()).isEqualTo(TypeNotification.RETOUR_FONDS_VALIDE);
        });
    }

    @Test
    void sans_autres_destinataires_tout_le_monde_recoit_le_lien_de_la_note() {
        when(users.findByRoles_NomAndActifTrue(RoleType.DFIN)).thenReturn(List.of(dfin));
        NoteFrais note = NoteFrais.builder().id(8L).reference("NF-2026-000008").build();

        service.notifierNote(List.of(RoleType.DFIN), emetteurBar, caissierEtBar, TypeNotification.RETOUR_FONDS_REFUSE,
            "Retour de fonds refusé", "Motif : argent non remis.", note);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(n.capture());
        assertThat(n.getAllValues()).extracting(x -> x.getDestinataire().getId()).containsExactly(1L, 3L);
        assertThat(n.getAllValues()).extracting(Notification::getLien).containsOnly("/notes-frais/8");
    }
}

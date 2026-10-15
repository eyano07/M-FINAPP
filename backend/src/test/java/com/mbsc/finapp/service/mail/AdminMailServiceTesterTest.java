package com.mbsc.finapp.service.mail;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.mail.MailTestRequest;
import com.mbsc.finapp.dto.mail.MailTestResponse;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.MailNotificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E-mail de test de la messagerie : l'identifiant de connexion de l'administrateur n'est plus forcément une
 * adresse (nom d'utilisateur) — il ne sert de destinataire que s'il en est une.
 */
class AdminMailServiceTesterTest {

    private final MailNotificationService mails = mock(MailNotificationService.class);
    private final CurrentUserProvider courant = mock(CurrentUserProvider.class);
    private final AdminMailService service = new AdminMailService(mock(ParametresMailService.class), mails, courant);

    private void administrateur(String identifiant, String adresseNotification) {
        when(courant.requireUser()).thenReturn(User.builder().email(identifiant).emailNotification(adresseNotification)
            .prenom("Bruno").build());
    }

    @Test
    void sans_adresse_nulle_part_rien_n_est_envoye_et_le_message_explique_quoi_faire() throws Exception {
        administrateur("admin", null);

        MailTestResponse r = service.tester(null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("Mail de notification").contains("pas une adresse e-mail");
        verify(mails, never()).envoyerTest(anyString(), anyString());
    }

    @Test
    void le_mail_de_notification_est_prefere_a_l_identifiant() throws Exception {
        administrateur("admin@mbsc.cd", "bruno@exemple.cd");

        assertThat(service.tester(null).ok()).isTrue();

        verify(mails).envoyerTest("bruno@exemple.cd", "Bruno");
    }

    @Test
    void un_identifiant_qui_est_une_adresse_sert_de_destinataire_par_defaut() throws Exception {
        administrateur("admin@mbsc.cd", null);

        assertThat(service.tester(null).ok()).isTrue();

        verify(mails).envoyerTest("admin@mbsc.cd", "Bruno");
    }

    @Test
    void un_nom_d_utilisateur_avec_un_mail_de_notification_envoie_au_mail_de_notification() throws Exception {
        administrateur("admin", "bruno@exemple.cd");

        assertThat(service.tester(null).ok()).isTrue();

        verify(mails).envoyerTest("bruno@exemple.cd", "Bruno");
    }

    @Test
    void une_adresse_saisie_a_la_main_l_emporte() throws Exception {
        administrateur("admin", null);

        assertThat(service.tester(new MailTestRequest(" test@exemple.cd ")).ok()).isTrue();

        verify(mails).envoyerTest("test@exemple.cd", "Bruno");
    }
}

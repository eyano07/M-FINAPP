package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.dto.auth.LoginRequest;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.JwtService;
import com.mbsc.finapp.security.LoginAttemptService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Connexion refusee : l'ecran de connexion affiche le message de l'exception, qui ne doit donc jamais etre le
 * « Bad credentials » de Spring Security (anglais, ou sa traduction selon la langue du navigateur) mais celui de
 * l'application, identique quel que soit le refus (identifiant inconnu ou mot de passe faux).
 */
class AuthServiceTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final LoginAttemptService tentatives = mock(LoginAttemptService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuthService service = new AuthService(authenticationManager, mock(JwtService.class),
        mock(UserRepository.class), tentatives, audit);

    @Test
    void un_mot_de_passe_faux_donne_un_message_en_francais_et_garde_la_trace_du_refus() {
        BadCredentialsException spring = new BadCredentialsException("Bad credentials");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenThrow(spring);

        assertThatThrownBy(() -> service.login(new LoginRequest("zv.compta", "mauvais")))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Identifiant ou mot de passe incorrect.")
            .hasMessageNotContaining("Bad credentials")
            .hasCause(spring);

        verify(tentatives).enregistrerEchec("zv.compta");
        verify(audit).authentification("CONNEXION", "zv.compta", null, false, "identifiants invalides");
    }

    @Test
    void un_compte_temporairement_bloque_garde_son_message_et_n_est_pas_authentifie() {
        when(tentatives.estBloque("zv.compta")).thenReturn(true);
        when(tentatives.minutesRestantes("zv.compta")).thenReturn(7L);

        assertThatThrownBy(() -> service.login(new LoginRequest("zv.compta", "x")))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Trop de tentatives de connexion. Reessayez dans 7 minute(s).");

        verify(authenticationManager, never()).authenticate(any());
        assertThat(AuthService.MESSAGE_IDENTIFIANTS_INCORRECTS).isEqualTo("Identifiant ou mot de passe incorrect.");
    }
}

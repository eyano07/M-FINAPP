package com.mbsc.finapp.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La connexion d'un compte desactive (a la main ou par la cascade des modules) dit « compte desactive » en
 * francais, et non le « User is disabled » de Spring ; un mauvais mot de passe garde son message habituel.
 */
class GlobalExceptionHandlerCompteDesactiveTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void un_compte_desactive_recoit_un_message_en_francais() {
        ResponseEntity<Map<String, Object>> reponse = handler.handleCompteDesactive(new DisabledException("User is disabled"));

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reponse.getBody()).isNotNull();
        assertThat(reponse.getBody().get("message")).isEqualTo("Ce compte est désactivé. Contactez l'administrateur.");
        assertThat(String.valueOf(reponse.getBody())).doesNotContain("User is disabled");
    }

    @Test
    void les_autres_echecs_d_authentification_ne_changent_pas() {
        ResponseEntity<Map<String, Object>> reponse = handler.handleAuth(new BadCredentialsException("Bad credentials"));

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reponse.getBody().get("message")).isEqualTo("Bad credentials");
    }
}

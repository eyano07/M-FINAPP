package com.mbsc.finapp.service.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class ErreurMailTest {

    @Test
    void classeLesEchecsCourants() {
        assertTrue(ErreurMail.expliquer(new MailSendException("x", new AuthenticationFailedException("535 bad"))).contains("mot de passe"));
        assertTrue(ErreurMail.expliquer(new MailSendException("x", new MessagingException("Could not connect", new UnknownHostException("smtp.nope")))).contains("introuvable"));
        assertTrue(ErreurMail.expliquer(new MailSendException("x", new MessagingException("c", new ConnectException("Connection refused")))).contains("Connexion impossible"));
        assertTrue(ErreurMail.expliquer(new MailSendException("x", new MessagingException("c", new SocketTimeoutException("Read timed out")))).contains("délai"));
        assertTrue(ErreurMail.expliquer(new MessagingException("PKIX path building failed")).contains("TLS"));
        assertTrue(ErreurMail.expliquer(new MessagingException("553 Sender address rejected")).contains("expédition"));
        assertTrue(ErreurMail.expliquer(new RuntimeException("autre chose")).startsWith("Envoi impossible"));
    }
}

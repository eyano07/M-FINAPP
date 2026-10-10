package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailNotificationServiceTest {

    @Test
    void htmlContientLienEtEchappeLeContenu() {
        String html = MailNotificationService.corpsHtml("MBSC", "#15803D", "Jo<b>", "Note <1>", "Montant & \"reste\"\nligne 2",
            "https://finapp.cd/notes-frais/42");
        assertTrue(html.contains("href=\"https://finapp.cd/notes-frais/42\""));
        assertTrue(html.contains("Ouvrir la notification"));
        assertTrue(html.contains("Jo&lt;b&gt;"));
        assertTrue(html.contains("Note &lt;1&gt;"));
        assertTrue(html.contains("Montant &amp; &quot;reste&quot;<br>ligne 2"));
        assertFalse(html.contains("<b>"));
    }

    @Test
    void sansLienPasDeBoutonEtCouleurInvalideRemplacee() {
        String html = MailNotificationService.corpsHtml("MBSC", "red;x", null, "T", "M", null);
        assertFalse(html.contains("Ouvrir la notification"));
        assertTrue(html.contains("#15803D"));
        assertFalse(html.contains("red;x"));
    }

    @Test
    void lienAbsoluRelatifVersUrlPublique() {
        assertEquals("https://finapp.cd/notes-frais/7", MailNotificationService.lienAbsolu("https://finapp.cd/", "/notes-frais/7"));
        assertEquals("https://autre.cd/x", MailNotificationService.lienAbsolu(null, "https://autre.cd/x"));
        assertNull(MailNotificationService.lienAbsolu("https://finapp.cd", null));
        assertNull(MailNotificationService.lienAbsolu("", "/x"));
    }
}

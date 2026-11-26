package com.mbsc.finapp.security;

import com.mbsc.finapp.service.hotellerie.IntegrationPmsService;
import com.mbsc.finapp.service.instance.SignatureReplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Appels de l'application Hôtellerie : signés avec la clé d'appairage, rejeu refusé (V152). */
class ClePmsFilterTest {

    private final SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
    private IntegrationPmsService integration;
    private ClePmsFilter filtre;

    @BeforeEach
    void preparer() {
        integration = mock(IntegrationPmsService.class);
        when(integration.cle()).thenReturn(Optional.of(cle));
        filtre = new ClePmsFilter(integration);
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requete(String methode, String chemin, String corps, String secret, String nonce) {
        MockHttpServletRequest r = new MockHttpServletRequest(methode, chemin);
        r.setServletPath(chemin);
        byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
        r.setContent(octets);
        long maintenant = System.currentTimeMillis();
        r.addHeader(ClePmsFilter.EN_TETE_APPAIRAGE, cle.appairageId());
        r.addHeader(ClePmsFilter.EN_TETE_HORODATAGE, Long.toString(maintenant));
        r.addHeader(ClePmsFilter.EN_TETE_NONCE, nonce);
        r.addHeader(ClePmsFilter.EN_TETE_SIGNATURE,
            SignatureReplication.signer(secret, cle.appairageId(), maintenant, nonce, methode, chemin, octets));
        return r;
    }

    @Test
    void unAppelSystemeSigneEstAuthentifieCommeHotellerie() throws Exception {
        AtomicReference<String> role = new AtomicReference<>();
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(requete("POST", "/pms/systeme/sso", "{\"code\":\"x\"}", cle.secret(), "n1"), reponse,
            new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                    role.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().iterator().next().getAuthority());
                }
            });
        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(role.get()).isEqualTo(ClePmsFilter.ROLE);
    }

    @Test
    void uneSignatureFausseOuUnRejeuSontRefuses() throws Exception {
        MockHttpServletResponse fausse = new MockHttpServletResponse();
        filtre.doFilter(requete("POST", "/pms/folios", "{}", "autre-secret-tres-long-0123456789abcdefghijkl", "n2"), fausse,
            new MockFilterChain());
        assertThat(fausse.getStatus()).isEqualTo(401);

        MockHttpServletResponse premiere = new MockHttpServletResponse();
        filtre.doFilter(requete("POST", "/pms/folios", "{}", cle.secret(), "n3"), premiere, new MockFilterChain());
        assertThat(premiere.getStatus()).isEqualTo(200);
        MockHttpServletResponse rejeu = new MockHttpServletResponse();
        filtre.doFilter(requete("POST", "/pms/folios", "{}", cle.secret(), "n3"), rejeu, new MockFilterChain());
        assertThat(rejeu.getStatus()).isEqualTo(401);
    }

    @Test
    void sansCleEnregistreeToutEstRefuse() throws Exception {
        when(integration.cle()).thenReturn(Optional.empty());
        MockHttpServletResponse r = new MockHttpServletResponse();
        filtre.doFilter(requete("GET", "/pms/moi", "", cle.secret(), "n4"), r, new MockFilterChain());
        assertThat(r.getStatus()).isEqualTo(403);
    }
}

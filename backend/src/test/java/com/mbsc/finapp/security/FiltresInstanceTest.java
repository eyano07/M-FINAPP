package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.service.instance.InstanceService;
import com.mbsc.finapp.service.instance.SignatureReplication;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Filtres du mode de l'instance (V132-V133) : appels entre serveurs signes (CleReplicationFilter) et serveur en lecture
 * seule (MiroirLectureSeuleFilter).
 */
class FiltresInstanceTest {

    private final InstanceService instance = mock(InstanceService.class);
    private final SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
    private ParametresInstance etat;

    @BeforeEach
    void preparer() {
        etat = ParametresInstance.builder().id(1L).mode(ModeInstance.EN_LIGNE).appairageId(cle.appairageId()).build();
        when(instance.etat()).thenAnswer(i -> etat);
        when(instance.mode()).thenAnswer(i -> etat.getMode());
        when(instance.cle()).thenReturn(Optional.of(cle));
    }

    @AfterEach
    void vider() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // Appels entre serveurs
    // ---------------------------------------------------------------------

    private MockHttpServletRequest appelSigne(String methode, String chemin, String corps, long horodatage, String nonce,
                                              String secret) {
        MockHttpServletRequest req = new MockHttpServletRequest(methode, "/api" + chemin);
        req.setServletPath(chemin);
        byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
        req.setContent(octets);
        req.addHeader(SignatureReplication.EN_TETE_APPAIRAGE, cle.appairageId());
        req.addHeader(SignatureReplication.EN_TETE_HORODATAGE, Long.toString(horodatage));
        req.addHeader(SignatureReplication.EN_TETE_NONCE, nonce);
        req.addHeader(SignatureReplication.EN_TETE_SIGNATURE,
            SignatureReplication.signer(secret, cle.appairageId(), horodatage, nonce, methode, chemin, octets));
        return req;
    }

    private record Passage(int statut, boolean suiteAppelee, String corps, String corpsVuParLaSuite) {}

    private Passage passer(CleReplicationFilter filtre, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse rep = new MockHttpServletResponse();
        String[] vu = {null};
        boolean[] appelee = {false};
        FilterChain suite = (r, s) -> {
            appelee[0] = true;
            vu[0] = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals(CleReplicationFilter.ROLE));
        };
        filtre.doFilter(req, rep, suite);
        return new Passage(rep.getStatus(), appelee[0], rep.getContentAsString(), vu[0]);
    }

    @Test
    void un_appel_bien_signe_passe_avec_son_corps_et_le_role_replication() throws Exception {
        CleReplicationFilter filtre = new CleReplicationFilter(instance);
        Passage p = passer(filtre, appelSigne("POST", "/replication/lot", "{\"a\":1}", System.currentTimeMillis(), "n1", cle.secret()));

        assertThat(p.suiteAppelee()).isTrue();
        assertThat(p.corpsVuParLaSuite()).isEqualTo("{\"a\":1}");
        verify(instance).noterContact();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("contexte vide apres l'appel").isNull();
    }

    @Test
    void signature_fausse_rejeu_horloge_decalee_et_appairage_inconnu_sont_refuses() throws Exception {
        CleReplicationFilter filtre = new CleReplicationFilter(instance);
        long maintenant = System.currentTimeMillis();

        assertThat(passer(filtre, appelSigne("POST", "/replication/lot", "{}", maintenant, "x1", "mauvais-secret")).statut()).isEqualTo(401);
        assertThat(passer(filtre, appelSigne("POST", "/replication/lot", "{}", maintenant - 6 * 60_000, "x2", cle.secret())).corps())
            .contains("Horloges");
        assertThat(passer(filtre, appelSigne("POST", "/replication/lot", "{}", maintenant, "x3", cle.secret())).statut()).isEqualTo(200);
        Passage rejoue = passer(filtre, appelSigne("POST", "/replication/lot", "{}", maintenant, "x3", cle.secret()));
        assertThat(rejoue.statut()).isEqualTo(401);
        assertThat(rejoue.corps()).contains("rejeu");

        MockHttpServletRequest sansSignature = new MockHttpServletRequest("GET", "/api/replication/ping");
        sansSignature.setServletPath("/replication/ping");
        assertThat(passer(filtre, sansSignature).statut()).isEqualTo(401);

        MockHttpServletRequest autreAppairage = appelSigne("GET", "/replication/ping", "", maintenant, "x4", cle.secret());
        autreAppairage.removeHeader(SignatureReplication.EN_TETE_APPAIRAGE);
        autreAppairage.addHeader(SignatureReplication.EN_TETE_APPAIRAGE, "00000000-0000-0000-0000-000000000000");
        assertThat(passer(filtre, autreAppairage).corps()).contains("Appairage inconnu");
    }

    @Test
    void un_appairage_revoque_recoit_le_code_qui_arrete_le_serveur_local() throws Exception {
        etat.setAppairageRevoque(cle.appairageId());
        Passage p = passer(new CleReplicationFilter(instance), appelSigne("GET", "/replication/ping", "", System.currentTimeMillis(), "r1", cle.secret()));

        assertThat(p.statut()).isEqualTo(410);
        assertThat(p.corps()).contains(CleReplicationFilter.CODE_REVOQUE);
        assertThat(p.suiteAppelee()).isFalse();
    }

    @Test
    void un_serveur_local_n_accepte_aucun_appel_de_synchronisation() throws Exception {
        etat.setMode(ModeInstance.LOCAL);
        Passage p = passer(new CleReplicationFilter(instance), appelSigne("GET", "/replication/ping", "", System.currentTimeMillis(), "l1", cle.secret()));

        assertThat(p.statut()).isEqualTo(403);
        assertThat(p.suiteAppelee()).isFalse();
        verify(instance, never()).noterContact();
    }

    @Test
    void le_filtre_de_signature_ignore_les_autres_routes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/notes-frais");
        req.setServletPath("/notes-frais");
        FilterChain suite = mock(FilterChain.class);
        new CleReplicationFilter(instance).doFilter(req, new MockHttpServletResponse(), suite);
        verify(suite).doFilter(any(), any());
    }

    // ---------------------------------------------------------------------
    // Lecture seule
    // ---------------------------------------------------------------------

    @Test
    void la_copie_en_ligne_refuse_les_saisies_mais_laisse_passer_lectures_et_actions_relayees() {
        ModeInstance m = ModeInstance.MIROIR;
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "GET", "/notes-frais")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/auth/login")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/replication/lot")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PUT", "/notifications/12/lue")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/admin/instance/reprendre-la-main")).isTrue();
        for (String action : new String[] {"verifier", "transmettre", "valider", "rejeter", "observation", "priorite", "soumettre"}) {
            assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais/12/" + action)).as(action).isTrue();
        }
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/actions-distantes/notes")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais/controle-budgetaire")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PATCH", "/admin/users/7/actif")).isTrue();

        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais")).as("creation directe").isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PUT", "/notes-frais/12")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais/12/annuler")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais/12/pieces-jointes")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/caisse/notes/12/payer")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/ventes")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PUT", "/admin/users/7")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PUT", "/profil/mot-de-passe")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "DELETE", "/notes-frais/12/pieces-jointes/3")).isFalse();
    }

    @Test
    void l_ancien_serveur_local_ne_relaie_rien() {
        ModeInstance m = ModeInstance.LOCAL_ARRETE;
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "GET", "/notes-frais")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/auth/login")).isTrue();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/notes-frais/12/valider")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "POST", "/actions-distantes/notes")).isFalse();
        assertThat(MiroirLectureSeuleFilter.estPermise(m, "PATCH", "/admin/users/7/actif")).isFalse();
        assertThat(MiroirLectureSeuleFilter.message(m)).contains("n'est plus le serveur principal");
    }

    @Test
    void le_filtre_de_lecture_seule_repond_423_avec_son_code_et_ne_gene_pas_un_serveur_principal() throws Exception {
        etat.setMode(ModeInstance.MIROIR);
        MiroirLectureSeuleFilter filtre = new MiroirLectureSeuleFilter(instance);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ventes");
        req.setServletPath("/ventes");
        MockHttpServletResponse rep = new MockHttpServletResponse();
        FilterChain suite = mock(FilterChain.class);

        filtre.doFilter(req, rep, suite);

        assertThat(rep.getStatus()).isEqualTo(423);
        assertThat(rep.getContentAsString()).contains(MiroirLectureSeuleFilter.CODE).contains("copie en ligne");
        verify(suite, never()).doFilter(any(), any());

        etat.setMode(ModeInstance.LOCAL);
        MockHttpServletResponse rep2 = new MockHttpServletResponse();
        FilterChain suite2 = mock(FilterChain.class);
        filtre.doFilter(req, rep2, suite2);
        verify(suite2).doFilter(any(), any());
        assertThat(ModeInstance.LOCAL.lectureSeule()).isFalse();
        assertThat(ModeInstance.EN_LIGNE.lectureSeule()).isFalse();
    }
}

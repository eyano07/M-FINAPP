package com.mbsc.finapp.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationMobileFilterTest {

    private final ApplicationMobileFilter filtre = new ApplicationMobileFilter();

    private MockHttpServletResponse passer(String methode, String chemin, String agent) throws Exception {
        MockHttpServletRequest requete = new MockHttpServletRequest(methode, "/api" + chemin);
        requete.setServletPath(chemin);
        if (agent != null) requete.addHeader("User-Agent", agent);
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(requete, reponse, new MockFilterChain());
        return reponse;
    }

    private static void connecter(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", null, roles));
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    private static final String TELEPHONE = "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile MBSCFinappAndroid/2.8";

    @Test
    void l_application_consulte_tout_et_decide_sur_les_notes() throws Exception {
        assertThat(passer("GET", "/comptabilite/pieces", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/verifier", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/valider", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/rejeter", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/auth/login", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/appareils", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notifications/3/lue", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/profil/mot-de-passe", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/profil/double-auth/confirmer", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/profil/preferences", TELEPHONE).getStatus()).isEqualTo(200);
    }

    @Test
    void l_application_ne_saisit_rien_d_autre() throws Exception {
        MockHttpServletResponse r = passer("POST", "/comptabilite/pieces", TELEPHONE);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains(ApplicationMobileFilter.CODE);
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais/12/soumettre", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("PUT", "/notes-frais/12", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("DELETE", "/notes-frais/12/pieces-jointes/4", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais/12/valider/autre", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("PUT", "/admin/securite/double-auth", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("PUT", "/profilX", TELEPHONE).getStatus()).isEqualTo(403);
    }

    @Test
    void le_caissier_encaisse_decaisse_et_vend_dans_l_application() throws Exception {
        connecter("ROLE_CAISSIER");
        // Trésorerie : caisse, banque, mobile money.
        assertThat(passer("POST", "/caisse/transactions", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/caisse/achats", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/caisse/notes/12/payer", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/caisse/notes/12/encaisser", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/caisse/retours-fonds/3/valider", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/banque/transactions", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/banque/notes/12/payer", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/mobile-money/transactions", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/mobile-money/notes/12/payer", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/ia/suggestion-compte", TELEPHONE).getStatus()).isEqualTo(200);
        // Vente, clients, fournisseurs.
        assertThat(passer("POST", "/ventes", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/ventes/7/valider", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/ventes/7/regler", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/clients", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/clients/4", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/fournisseurs", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/fournisseurs/4", TELEPHONE).getStatus()).isEqualTo(200);
        // Ses notes de frais : création, pièces jointes, soumission (ce qu'il peut faire de chaque note : NoteFraisService).
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/soumettre", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/pieces-jointes", TELEPHONE).getStatus()).isEqualTo(200);
        // Le reste reste fermé, même au caissier.
        assertThat(passer("POST", "/comptabilite/pieces", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/ventesX", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/ia/analyse", TELEPHONE).getStatus()).isEqualTo(403);
    }

    @Test
    void les_autres_roles_n_operent_pas_la_tresorerie_dans_l_application() throws Exception {
        connecter("ROLE_DFIN", "ROLE_ADMIN");
        assertThat(passer("POST", "/caisse/transactions", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/banque/transactions", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/ventes", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/fournisseurs", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais/12/valider", TELEPHONE).getStatus()).isEqualTo(200);
    }

    @Test
    void le_comptable_cree_ses_notes_de_frais_dans_l_application() throws Exception {
        connecter("ROLE_COMPTABLE");
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/controle-budgetaire", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/pieces-jointes", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/soumettre", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/actions-distantes/notes", TELEPHONE).getStatus()).isEqualTo(200);
        // Ni trésorerie, ni vente, ni pièces comptables depuis le téléphone.
        assertThat(passer("POST", "/caisse/transactions", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/ventes", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/comptabilite/pieces", TELEPHONE).getStatus()).isEqualTo(403);
    }

    @Test
    void le_dfin_corrige_libelle_comptes_et_service_le_da_decide_seulement() throws Exception {
        connecter("ROLE_DFIN");
        assertThat(passer("PUT", "/notes-frais/12/objet", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notes-frais/12/comptes", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notes-frais/12/service", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/observation", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/verifier", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notes-frais/12", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("PUT", "/notes-frais/parametres-priorite", TELEPHONE).getStatus()).isEqualTo(403);
        SecurityContextHolder.clearContext();
        connecter("ROLE_DA");
        assertThat(passer("POST", "/notes-frais/12/valider", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/rejeter", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/observation", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/notes-frais/12/priorite", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notes-frais/parametres-priorite", TELEPHONE).getStatus()).isEqualTo(200);
        assertThat(passer("PUT", "/notes-frais/12/objet", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("PUT", "/notes-frais/12/comptes", TELEPHONE).getStatus()).isEqualTo(403);
        assertThat(passer("POST", "/notes-frais", TELEPHONE).getStatus()).isEqualTo(403);
    }

    @Test
    void le_site_n_est_pas_concerne() throws Exception {
        assertThat(passer("POST", "/comptabilite/pieces", "Mozilla/5.0 (X11; Linux) Chrome/130").getStatus()).isEqualTo(200);
        assertThat(passer("POST", "/comptabilite/pieces", null).getStatus()).isEqualTo(200);
    }
}

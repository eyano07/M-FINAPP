package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.admin.UserCreateRequest;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.dto.admin.UserUpdateRequest;
import com.mbsc.finapp.dto.profil.ChangerMotDePasseRequest;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.PreferenceUtilisateurRepository;
import com.mbsc.finapp.repository.RoleRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.service.AdminService;
import com.mbsc.finapp.service.DocumentationCompteService;
import com.mbsc.finapp.service.EtatModules;
import com.mbsc.finapp.service.PermissionService;
import com.mbsc.finapp.service.ProfilService;
import com.mbsc.finapp.service.RolesDisponibles;
import com.mbsc.finapp.service.StorageService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mot de passe provisoire (V131) : un compte cree par l'administrateur, ou dont il redefinit le mot de passe, doit
 * choisir son propre mot de passe a la premiere connexion ; jusque-la, seuls le profil et le changement de mot de passe
 * lui sont ouverts, l'API comprise.
 */
class MotDePasseProvisoireTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encodeur = mock(PasswordEncoder.class);
    private final RolesDisponibles rolesDisponibles = mock(RolesDisponibles.class);
    private final CurrentUserProvider courant = mock(CurrentUserProvider.class);
    private final AdminService admin = new AdminService(
        mock(CompteOHADARepository.class), mock(EcritureGrandLivreRepository.class), users, roles,
        mock(TauxChangeRepository.class), encodeur, courant,
        mock(DocumentationCompteService.class), mock(PermissionService.class), rolesDisponibles);
    private final ProfilService profil = new ProfilService(
        users, courant, mock(StorageService.class), encodeur, mock(PreferenceUtilisateurRepository.class));

    private final User administrateur = User.builder().id(1L).email("admin@mbsc.cd").build();

    @BeforeEach
    void preparer() {
        when(rolesDisponibles.etat()).thenReturn(EtatModules.de(List.of()));
        when(roles.findByNom(any(RoleType.class)))
            .thenAnswer(inv -> Optional.of(Role.builder().nom(inv.getArgument(0)).build()));
        when(encodeur.encode(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
        when(encodeur.matches(anyString(), anyString()))
            .thenAnswer(inv -> ("hash:" + inv.getArgument(0)).equals(inv.getArgument(1)));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(courant.requireUser()).thenReturn(administrateur);
    }

    @AfterEach
    void viderContexte() {
        SecurityContextHolder.clearContext();
    }

    private User compte(long id, String motDePasse, boolean provisoire) {
        Set<Role> ensemble = new HashSet<>(Set.of(Role.builder().nom(RoleType.DFIN).build()));
        User u = User.builder().id(id).email("compte" + id).nom("N").prenom("P").motDePasse("hash:" + motDePasse)
            .doitChangerMotDePasse(provisoire).roles(ensemble).build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    // ---------------------------------------------------------------------
    // Pose du drapeau par l'administrateur
    // ---------------------------------------------------------------------

    @Test
    void un_compte_cree_par_l_administrateur_a_un_mot_de_passe_provisoire() {
        UserResponse cree = admin.creerUtilisateur(new UserCreateRequest("Kalunga", "Bruno", "bruno", "provisoire-1",
            null, null, null, null, List.of("DFIN")));

        ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
        verify(users).save(enregistre.capture());
        assertThat(enregistre.getValue().isDoitChangerMotDePasse()).isTrue();
        assertThat(cree.motDePasseProvisoire()).isTrue();
    }

    @Test
    void un_mot_de_passe_redefini_par_l_administrateur_redevient_provisoire_et_ferme_les_sessions() {
        User u = compte(5L, "ancien-mdp", false);
        int versionAvant = u.getVersionJetons();

        admin.modifierUtilisateur(5L, new UserUpdateRequest("N", "P", "nouveau-mdp", null, null, null, null, List.of("DFIN")));

        assertThat(u.isDoitChangerMotDePasse()).isTrue();
        assertThat(u.getVersionJetons()).isEqualTo(versionAvant + 1);
    }

    @Test
    void une_modification_sans_mot_de_passe_ne_touche_pas_au_drapeau() {
        User u = compte(5L, "ancien-mdp", false);

        admin.modifierUtilisateur(5L, new UserUpdateRequest("N", "P", null, null, null, null, null, List.of("DFIN")));

        assertThat(u.isDoitChangerMotDePasse()).isFalse();
    }

    @Test
    void l_administrateur_qui_change_son_propre_mot_de_passe_n_est_pas_bloque() {
        User soiMeme = compte(1L, "ancien-mdp", false);
        when(courant.requireUser()).thenReturn(soiMeme);

        admin.modifierUtilisateur(1L, new UserUpdateRequest("N", "P", "nouveau-mdp", null, null, null, null, List.of("DFIN")));

        assertThat(soiMeme.isDoitChangerMotDePasse()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Levee du drapeau par l'utilisateur
    // ---------------------------------------------------------------------

    @Test
    void choisir_son_mot_de_passe_debloque_le_compte_et_ferme_les_sessions() {
        User u = compte(5L, "provisoire-1", true);
        when(courant.requireUser()).thenReturn(u);
        int versionAvant = u.getVersionJetons();

        profil.changerMotDePasse(new ChangerMotDePasseRequest("provisoire-1", "le-mien-2026"));

        assertThat(u.isDoitChangerMotDePasse()).isFalse();
        assertThat(u.getMotDePasse()).isEqualTo("hash:le-mien-2026");
        assertThat(u.getVersionJetons()).isEqualTo(versionAvant + 1);
    }

    @Test
    void garder_le_meme_mot_de_passe_est_refuse() {
        User u = compte(5L, "provisoire-1", true);
        when(courant.requireUser()).thenReturn(u);

        assertThatThrownBy(() -> profil.changerMotDePasse(new ChangerMotDePasseRequest("provisoire-1", "provisoire-1")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(u.isDoitChangerMotDePasse()).isTrue();
        verify(users, never()).save(any());
    }

    @Test
    void un_mauvais_mot_de_passe_actuel_reste_refuse() {
        User u = compte(5L, "provisoire-1", true);
        when(courant.requireUser()).thenReturn(u);

        assertThatThrownBy(() -> profil.changerMotDePasse(new ChangerMotDePasseRequest("faux", "le-mien-2026")))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(u.isDoitChangerMotDePasse()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Blocage de l'API
    // ---------------------------------------------------------------------

    private MockHttpServletResponse requete(User connecte, String methode, String chemin) throws Exception {
        if (connecte != null) {
            UserPrincipal principal = new UserPrincipal(connecte);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        }
        MockHttpServletRequest req = new MockHttpServletRequest(methode, "/api" + chemin);
        req.setServletPath(chemin);
        MockHttpServletResponse rep = new MockHttpServletResponse();
        FilterChain suite = mock(FilterChain.class);
        new MotDePasseProvisoireFilter().doFilter(req, rep, suite);
        if (rep.getStatus() == 200) {
            verify(suite).doFilter(req, rep);
        }
        return rep;
    }

    @Test
    void un_compte_provisoire_ne_peut_que_lire_son_profil_et_changer_son_mot_de_passe() throws Exception {
        User u = compte(5L, "provisoire-1", true);

        assertThat(requete(u, "GET", "/profil").getStatus()).isEqualTo(200);
        assertThat(requete(u, "PUT", "/profil/mot-de-passe").getStatus()).isEqualTo(200);
        assertThat(requete(u, "POST", "/auth/logout").getStatus()).isEqualTo(200);
        assertThat(requete(u, "POST", "/auth/refresh").getStatus()).isEqualTo(200);
        assertThat(requete(u, "GET", "/parametres").getStatus()).isEqualTo(200);

        for (String[] interdit : new String[][] {
            {"GET", "/notes-frais"}, {"POST", "/notes-frais"}, {"GET", "/caisse/transactions"}, {"PUT", "/profil"},
            {"GET", "/profil/preferences"}, {"GET", "/notifications"}, {"POST", "/profil/photo"}, {"GET", "/admin/users"}}) {
            MockHttpServletResponse rep = requete(u, interdit[0], interdit[1]);
            assertThat(rep.getStatus()).as(interdit[0] + " " + interdit[1]).isEqualTo(403);
            assertThat(rep.getContentAsString()).contains(MotDePasseProvisoireFilter.CODE);
        }
    }

    @Test
    void un_compte_ordinaire_et_une_requete_anonyme_ne_sont_pas_concernes() throws Exception {
        User u = compte(6L, "le-mien", false);

        assertThat(requete(u, "GET", "/notes-frais").getStatus()).isEqualTo(200);
        assertThat(requete(u, "POST", "/caisse/transactions").getStatus()).isEqualTo(200);
        SecurityContextHolder.clearContext();
        assertThat(requete(null, "GET", "/notes-frais").getStatus()).isEqualTo(200);
    }
}

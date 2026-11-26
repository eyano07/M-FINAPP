package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.dto.admin.UserUpdateRequest;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.RoleRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mbsc.finapp.dto.admin.UserCreateRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gestion des utilisateurs quand un module est desactive : le role du module n'est plus propose, les comptes qui
 * n'ont plus que de tels roles sont masques, et un role masque deja porte par un compte n'est jamais perdu.
 */
class AdministrationUtilisateursModulesTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encodeur = mock(PasswordEncoder.class);
    private final RolesDisponibles rolesDisponibles = mock(RolesDisponibles.class);
    private final AdminService service = new AdminService(
        mock(CompteOHADARepository.class), mock(EcritureGrandLivreRepository.class), users, roles,
        mock(TauxChangeRepository.class), encodeur, mock(CurrentUserProvider.class),
        mock(DocumentationCompteService.class), mock(PermissionService.class), rolesDisponibles);

    @BeforeEach
    void preparer() {
        for (RoleType type : RoleType.values()) {
            when(roles.findByNom(type)).thenReturn(Optional.of(roleDe(type)));
        }
        when(encodeur.encode(anyString())).thenReturn("hash");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        restaurantCoupe(false);
    }

    /** Un seul objet Role par type, comme dans la base : l'egalite des ensembles de roles en depend. */
    private final java.util.Map<RoleType, Role> catalogue = new java.util.EnumMap<>(RoleType.class);

    private Role roleDe(RoleType type) {
        return catalogue.computeIfAbsent(type, t -> Role.builder().nom(t).build());
    }

    private void restaurantCoupe(boolean coupe) {
        List<ModuleConfig> lignes = List.of(ModuleConfig.builder().module(ModuleMetier.RESTAURANT).actif(!coupe).build());
        when(rolesDisponibles.etat()).thenReturn(EtatModules.de(lignes));
        when(rolesDisponibles.disponibles()).thenReturn(
            java.util.Arrays.stream(RoleType.values()).filter(r -> !coupe || !List.of(
                RoleType.RESP_RESTAURANT, RoleType.RESP_CUISINE, RoleType.RESP_BAR).contains(r)).toList());
    }

    private User compte(long id, boolean actif, RoleType... types) {
        Set<Role> ensemble = new HashSet<>();
        for (RoleType t : types) {
            ensemble.add(roleDe(t));
        }
        User u = User.builder().id(id).email("compte" + id).prenom("P" + id).nom("N" + id).actif(actif).roles(ensemble).build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static UserUpdateRequest modification(String... roles) {
        return new UserUpdateRequest("N", "P", null, null, null, null, null, List.of(roles));
    }

    private static Set<RoleType> types(User u) {
        return u.getRoles().stream().map(Role::getNom).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------------
    // Liste
    // ---------------------------------------------------------------------

    @Test
    void la_liste_masque_les_comptes_dont_tous_les_roles_sont_coupes_meme_inactifs_et_filtre_les_autres() {
        restaurantCoupe(true);
        User bar = compte(1, true, RoleType.RESP_BAR);
        User barInactif = compte(2, false, RoleType.RESP_CUISINE);
        User directeur = compte(3, true, RoleType.DFIN, RoleType.RESP_BAR);
        User caissier = compte(4, true, RoleType.CAISSIER);
        when(users.findAllByOrderByNomAscPrenomAsc()).thenReturn(List.of(bar, barInactif, directeur, caissier));

        List<UserResponse> liste = service.listerUtilisateurs();

        assertThat(liste).extracting(UserResponse::id).containsExactly(3L, 4L);
        // Le role masque du directeur ne s'affiche plus, mais il reste porte par le compte.
        assertThat(liste.get(0).roles()).containsExactly("DFIN");
        assertThat(types(directeur)).contains(RoleType.RESP_BAR);
    }

    @Test
    void la_liste_montre_tout_quand_aucun_module_n_est_coupe() {
        User bar = compte(1, true, RoleType.RESP_BAR);
        when(users.findAllByOrderByNomAscPrenomAsc()).thenReturn(List.of(bar));

        assertThat(service.listerUtilisateurs()).extracting(UserResponse::roles).containsExactly(List.of("RESP_BAR"));
    }

    // ---------------------------------------------------------------------
    // Creation
    // ---------------------------------------------------------------------

    private static UserCreateRequest creation(String... roles) {
        return new UserCreateRequest("Nom", "Prenom", "nouveau", "motdepasse-ok", null, null, null, null, List.of(roles));
    }

    @Test
    void creer_un_compte_avec_un_role_dont_le_module_est_coupe_est_refuse() {
        restaurantCoupe(true);

        assertThatThrownBy(() -> service.creerUtilisateur(creation("RESP_CUISINE")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).contains("RESP_CUISINE").contains("désactivé");
            });
        verify(users, never()).save(any());
    }

    @Test
    void creer_un_compte_avec_un_role_disponible_reste_possible_quand_un_autre_module_est_coupe() {
        restaurantCoupe(true);

        UserResponse cree = service.creerUtilisateur(creation("CAISSIER"));

        assertThat(cree.roles()).containsExactly("CAISSIER");
    }

    // ---------------------------------------------------------------------
    // Modification
    // ---------------------------------------------------------------------

    @Test
    void modifier_un_compte_multi_roles_conserve_le_role_masque_que_l_ecran_ne_montre_pas() {
        restaurantCoupe(true);
        User directeur = compte(1, true, RoleType.DFIN, RoleType.RESP_BAR);

        // L'ecran ne connait plus RESP_BAR : il renvoie DFIN et CAISSIER.
        UserResponse reponse = service.modifierUtilisateur(1L, modification("DFIN", "CAISSIER"));

        assertThat(types(directeur)).containsExactlyInAnyOrder(RoleType.DFIN, RoleType.CAISSIER, RoleType.RESP_BAR);
        assertThat(reponse.roles()).containsExactly("CAISSIER", "DFIN");
    }

    @Test
    void ajouter_un_role_dont_le_module_est_coupe_est_refuse_mais_en_garder_un_deja_porte_ne_l_est_pas() {
        restaurantCoupe(true);
        User directeur = compte(1, true, RoleType.DFIN, RoleType.RESP_BAR);

        assertThatThrownBy(() -> service.modifierUtilisateur(1L, modification("DFIN", "RESP_CUISINE")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // Renvoyer le role masque deja porte (client qui connait tous les roles) ne pose aucun probleme.
        service.modifierUtilisateur(1L, modification("DFIN", "RESP_BAR"));
        assertThat(types(directeur)).containsExactlyInAnyOrder(RoleType.DFIN, RoleType.RESP_BAR);
    }

    @Test
    void un_compte_ne_peut_pas_se_retrouver_avec_que_des_roles_coupes() {
        restaurantCoupe(true);
        compte(1, true, RoleType.DFIN, RoleType.RESP_BAR);

        // Retirer DFIN laisserait le seul role RESP_BAR, masque : le compte serait bloque.
        assertThatThrownBy(() -> service.modifierUtilisateur(1L, modification("RESP_BAR")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void modifier_ou_activer_un_compte_masque_est_refuse() {
        restaurantCoupe(true);
        compte(1, true, RoleType.RESP_BAR);

        assertThatThrownBy(() -> service.modifierUtilisateur(1L, modification("DFIN")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.toggleActif(1L))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void sans_module_coupe_la_modification_remplace_les_roles_comme_avant() {
        User u = compte(1, true, RoleType.DFIN, RoleType.RESP_BAR);

        service.modifierUtilisateur(1L, modification("CAISSIER"));

        assertThat(types(u)).containsExactly(RoleType.CAISSIER);
    }

    // ---------------------------------------------------------------------
    // Activation manuelle et roles proposes
    // ---------------------------------------------------------------------

    @Test
    void la_bascule_manuelle_d_un_compte_visible_continue_de_fonctionner_et_revoque_les_jetons() {
        User u = compte(1, true, RoleType.DFIN);
        int version = u.getVersionJetons();

        UserResponse reponse = service.toggleActif(1L);

        assertThat(reponse.actif()).isFalse();
        assertThat(u.getVersionJetons()).isEqualTo(version + 1);
        ArgumentCaptor<User> sauve = ArgumentCaptor.forClass(User.class);
        verify(users).save(sauve.capture());
        assertThat(sauve.getValue().isDesactiveParModule()).as("une desactivation manuelle ne pose jamais le drapeau").isFalse();
    }

    @Test
    void les_roles_proposes_sont_ceux_dont_le_module_n_est_pas_coupe() {
        restaurantCoupe(true);

        assertThat(service.rolesDisponibles())
            .doesNotContain(RoleType.RESP_RESTAURANT, RoleType.RESP_CUISINE, RoleType.RESP_BAR)
            .contains(RoleType.ADMIN, RoleType.CAISSIER, RoleType.DFIN);
    }
}

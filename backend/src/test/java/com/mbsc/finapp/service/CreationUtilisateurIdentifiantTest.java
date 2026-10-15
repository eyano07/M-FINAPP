package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.admin.UserCreateRequest;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.RoleRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Création d'un utilisateur avec un nom d'utilisateur comme identifiant de connexion (« dfin_bruno ») :
 * enregistré en minuscules, unique sans tenir compte de la casse.
 */
class CreationUtilisateurIdentifiantTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encodeur = mock(PasswordEncoder.class);
    private final AdminService service = new AdminService(
        mock(CompteOHADARepository.class), mock(EcritureGrandLivreRepository.class), users, roles,
        mock(TauxChangeRepository.class), encodeur, mock(CurrentUserProvider.class),
        mock(DocumentationCompteService.class), mock(PermissionService.class));

    private static UserCreateRequest demande(String identifiant) {
        return new UserCreateRequest("Kalunga", "Bruno", identifiant, "motdepasse-ok", "094589334", null,
            "Direction Financière", "Lubumbashi", List.of("DFIN"));
    }

    private void preparer() {
        when(roles.findByNom(RoleType.DFIN)).thenReturn(Optional.of(Role.builder().nom(RoleType.DFIN).build()));
        when(encodeur.encode(anyString())).thenReturn("hash");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void un_nom_d_utilisateur_est_enregistre_en_minuscules() {
        preparer();

        UserResponse cree = service.creerUtilisateur(demande("Dfin_Bruno"));

        ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
        verify(users).save(enregistre.capture());
        assertThat(enregistre.getValue().getEmail()).isEqualTo("dfin_bruno");
        assertThat(cree.email()).isEqualTo("dfin_bruno");
        assertThat(cree.roles()).containsExactly("DFIN");
    }

    @Test
    void l_unicite_ignore_la_casse() {
        preparer();
        when(users.existsByEmailIgnoreCase("dfin_bruno")).thenReturn(true);

        assertThatThrownBy(() -> service.creerUtilisateur(demande("DFIN_BRUNO")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).contains("identifiant");
            });
        verify(users, never()).save(any());
    }

    @Test
    void une_adresse_e_mail_reste_un_identifiant_valable() {
        preparer();

        assertThat(service.creerUtilisateur(demande("Bruno.Kalunga@MBSC.cd")).email()).isEqualTo("bruno.kalunga@mbsc.cd");
    }
}

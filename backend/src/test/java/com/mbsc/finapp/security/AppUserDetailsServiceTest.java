package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recherche du compte à la connexion (et à chaque requête, par le sujet du jeton) : l'identifiant est une
 * adresse ou un nom d'utilisateur, retrouvé sans tenir compte de la casse.
 */
class AppUserDetailsServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final AppUserDetailsService service = new AppUserDetailsService(repository);

    private static User compte(String identifiant) {
        return User.builder().id(7L).email(identifiant).motDePasse("hash")
            .roles(Set.of(Role.builder().nom(RoleType.DFIN).build())).build();
    }

    @Test
    void un_nom_d_utilisateur_retrouve_son_compte() {
        when(repository.findByEmail("dfin_bruno")).thenReturn(Optional.of(compte("dfin_bruno")));

        UserDetails d = service.loadUserByUsername("dfin_bruno");

        assertThat(d.getUsername()).isEqualTo("dfin_bruno");
        assertThat(d.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_DFIN");
        verify(repository, never()).findAllByEmailIgnoreCase(anyString());
    }

    @Test
    void la_casse_et_les_espaces_autour_sont_ignores() {
        when(repository.findByEmail("Dfin_Bruno")).thenReturn(Optional.empty());
        when(repository.findAllByEmailIgnoreCase("Dfin_Bruno")).thenReturn(List.of(compte("dfin_bruno")));

        assertThat(service.loadUserByUsername("  Dfin_Bruno ").getUsername()).isEqualTo("dfin_bruno");
    }

    /** Le jeton porte l'identifiant tel qu'enregistré, même s'il contient des majuscules (ancien compte) : correspondance exacte. */
    @Test
    void un_ancien_compte_enregistre_avec_des_majuscules_se_retrouve_par_son_jeton() {
        when(repository.findByEmail("Admin@MBSC.cd")).thenReturn(Optional.of(compte("Admin@MBSC.cd")));

        assertThat(service.loadUserByUsername("Admin@MBSC.cd").getUsername()).isEqualTo("Admin@MBSC.cd");
        verify(repository, never()).findAllByEmailIgnoreCase(anyString());
    }

    /** Deux anciens comptes qui ne diffèrent que par la casse : on ne devine pas lequel est visé. */
    @Test
    void plusieurs_comptes_a_la_casse_pres_ne_sont_jamais_devines() {
        when(repository.findByEmail("jean")).thenReturn(Optional.empty());
        when(repository.findAllByEmailIgnoreCase("jean")).thenReturn(List.of(compte("Jean"), compte("JEAN")));

        assertThatThrownBy(() -> service.loadUserByUsername("jean")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void un_identifiant_inconnu_ou_vide_est_refuse() {
        when(repository.findByEmail("inconnu")).thenReturn(Optional.empty());
        when(repository.findAllByEmailIgnoreCase("inconnu")).thenReturn(List.of());

        assertThatThrownBy(() -> service.loadUserByUsername("inconnu")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername("   ")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername(null)).isInstanceOf(UsernameNotFoundException.class);
        verify(repository, never()).findByEmail("");
    }
}

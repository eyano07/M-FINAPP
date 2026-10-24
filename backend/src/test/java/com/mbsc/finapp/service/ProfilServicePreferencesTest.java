package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.PreferenceUtilisateur;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.repository.PreferenceUtilisateurRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Preferences d'affichage enregistrees sur le compte (V130) : liste blanche des cles et des valeurs, une seule ligne par
 * utilisateur et par cle, jamais les preferences d'un autre utilisateur.
 */
class ProfilServicePreferencesTest {

    private final CurrentUserProvider courant = mock(CurrentUserProvider.class);
    private final PreferenceUtilisateurRepository prefs = mock(PreferenceUtilisateurRepository.class);
    private final ProfilService service = new ProfilService(
        mock(UserRepository.class), courant, mock(StorageService.class), mock(PasswordEncoder.class), prefs);

    private final User anne = User.builder().id(7L).email("anne").build();

    private static PreferenceUtilisateur ligne(User u, String cle, String valeur) {
        return PreferenceUtilisateur.builder().user(u).cle(cle).valeur(valeur).build();
    }

    @Test
    void une_cle_inconnue_est_refusee_et_rien_n_est_stocke() {
        when(courant.requireUser()).thenReturn(anne);

        assertThatThrownBy(() -> service.definirPreference("autre.reglage", "CARTES"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(prefs, never()).save(any());
    }

    @Test
    void une_valeur_hors_liste_est_refusee_et_rien_n_est_stocke() {
        when(courant.requireUser()).thenReturn(anne);

        assertThatThrownBy(() -> service.definirPreference("notes.affichage", "GRILLE"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // La casse compte : « tableau » n'est pas « TABLEAU ».
        assertThatThrownBy(() -> service.definirPreference("notes.affichage", "tableau"))
            .isInstanceOf(ResponseStatusException.class);
        verify(prefs, never()).save(any());
    }

    @Test
    void la_premiere_definition_cree_la_ligne_de_l_appelant() {
        when(courant.requireUser()).thenReturn(anne);
        when(prefs.findByUserIdAndCle(7L, "notes.affichage")).thenReturn(Optional.empty());
        when(prefs.findByUserId(7L)).thenReturn(List.of(ligne(anne, "notes.affichage", "TABLEAU")));

        Map<String, String> resultat = service.definirPreference("notes.affichage", "TABLEAU");

        ArgumentCaptor<PreferenceUtilisateur> enregistree = ArgumentCaptor.forClass(PreferenceUtilisateur.class);
        verify(prefs).save(enregistree.capture());
        assertThat(enregistree.getValue().getUser()).isSameAs(anne);
        assertThat(enregistree.getValue().getCle()).isEqualTo("notes.affichage");
        assertThat(enregistree.getValue().getValeur()).isEqualTo("TABLEAU");
        assertThat(resultat).containsExactly(Map.entry("notes.affichage", "TABLEAU"));
    }

    @Test
    void une_seconde_definition_met_a_jour_la_meme_ligne_sans_doublon() {
        PreferenceUtilisateur existante = ligne(anne, "notes.affichage", "TABLEAU");
        when(courant.requireUser()).thenReturn(anne);
        when(prefs.findByUserIdAndCle(7L, "notes.affichage")).thenReturn(Optional.of(existante));
        when(prefs.findByUserId(7L)).thenReturn(List.of(existante));

        Map<String, String> resultat = service.definirPreference("notes.affichage", "CARTES");

        // La ligne existante est modifiee puis enregistree : aucune nouvelle ligne (la contrainte UNIQUE (user_id, cle) le garantit aussi).
        ArgumentCaptor<PreferenceUtilisateur> enregistree = ArgumentCaptor.forClass(PreferenceUtilisateur.class);
        verify(prefs, times(1)).save(enregistree.capture());
        assertThat(enregistree.getValue()).isSameAs(existante);
        assertThat(existante.getValeur()).isEqualTo("CARTES");
        assertThat(resultat).containsExactly(Map.entry("notes.affichage", "CARTES"));
    }

    @Test
    void les_preferences_lues_sont_celles_de_l_appelant_seulement() {
        User paul = User.builder().id(8L).email("paul").build();
        when(courant.requireUser()).thenReturn(paul);
        when(prefs.findByUserId(8L)).thenReturn(List.of());
        when(prefs.findByUserId(7L)).thenReturn(List.of(ligne(anne, "notes.affichage", "TABLEAU")));

        assertThat(service.preferences()).isEmpty();                 // aucune ligne pour Paul : valeur par defaut de l'ecran
        verify(prefs).findByUserId(8L);
        verify(prefs, never()).findByUserId(7L);                     // la ligne d'Anne n'est jamais lue pour Paul

        when(courant.requireUser()).thenReturn(anne);
        assertThat(service.preferences()).containsExactly(Map.entry("notes.affichage", "TABLEAU"));
    }

    @Test
    void la_table_est_unique_par_utilisateur_et_cle_et_supprimee_avec_l_utilisateur() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V130__preferences_utilisateur.sql"));
        assertThat(sql).contains("REFERENCES users(id) ON DELETE CASCADE");
        assertThat(sql).contains("UNIQUE (user_id, cle)");
    }
}

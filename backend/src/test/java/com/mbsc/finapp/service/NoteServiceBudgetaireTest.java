package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rattachement d'une note de frais a un service : le createur avant la soumission, le DFIN pendant la verification
 * (controle budgetaire refait sur l'enveloppe du nouveau service), jamais une note d'encaissement ni un service desactive.
 */
class NoteServiceBudgetaireTest {

    private NoteFraisRepository notes;
    private CurrentUserProvider utilisateur;
    private ControleBudgetaireService controle;
    private ServiceBudgetaireRepository services;
    private NoteFraisService service;
    private User demandeur;
    private User dfin;

    private static final ServiceBudgetaire RESTAURANT = BudgetParServiceTest.RESTAURANT;
    private static final ServiceBudgetaire LOGISTIQUE = BudgetParServiceTest.LOGISTIQUE;
    private static final ServiceBudgetaire FERME = ServiceBudgetaire.builder().id(8L).nom("Fermé").responsable("X").actif(false).build();

    @BeforeEach
    void preparer() throws Exception {
        Constructor<?> constructeur = NoteFraisService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (NoteFraisService) constructeur.newInstance(arguments);
        notes = trouver(arguments, NoteFraisRepository.class);
        utilisateur = trouver(arguments, CurrentUserProvider.class);
        controle = trouver(arguments, ControleBudgetaireService.class);
        services = trouver(arguments, ServiceBudgetaireRepository.class);

        demandeur = User.builder().id(5L).prenom("Papy").nom("Penze").email("compta@mbsc.cd").build();
        dfin = User.builder().id(6L).prenom("Brino").nom("Kalunga").email("dfin_bruno")
            .roles(Set.of(com.mbsc.finapp.domain.Role.builder().nom(com.mbsc.finapp.domain.enums.RoleType.DFIN).build())).build();
        when(notes.saveAndFlush(any(NoteFrais.class))).thenAnswer(i -> i.getArgument(0));
        when(services.findById(1L)).thenReturn(Optional.of(RESTAURANT));
        when(services.findById(2L)).thenReturn(Optional.of(LOGISTIQUE));
        when(services.findById(8L)).thenReturn(Optional.of(FERME));
        when(controle.controlerNote(any(NoteFrais.class))).thenReturn(new ControleBudgetaireResponse(
            2026, 12, 10L, "BUD-2026-000010", "Décembre", StatutControleBudget.CONFORME, false, "USD", null, List.of(), List.of(), true));
    }

    @SuppressWarnings("unchecked")
    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return (T) Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow();
    }

    private void connecte(User u) {
        when(utilisateur.requireUser()).thenReturn(u);
        when(utilisateur.requireUserId()).thenReturn(u.getId());
        when(utilisateur.requirePrincipal()).thenReturn(new UserPrincipal(u));
    }

    private NoteFrais note(StatutNote statut, SensTransaction sens, ServiceBudgetaire service) {
        NoteFrais note = NoteFrais.builder().id(2L).reference("NF-2026-000002").objet("Achat de vivres")
            .montant(new BigDecimal("150")).devise(Devise.USD).statut(statut).sens(sens).createur(demandeur).service(service).build();
        when(notes.findWithDetailsById(2L)).thenReturn(Optional.of(note));
        return note;
    }

    @Test
    void le_createur_choisit_le_service_d_une_note_en_brouillon() {
        connecte(demandeur);
        NoteFrais note = note(StatutNote.BROUILLON, SensTransaction.DECAISSEMENT, null);

        NoteFraisDetailResponse r = service.modifierService(2L, 1L);

        assertThat(note.getService()).isSameAs(RESTAURANT);
        assertThat(r.serviceNom()).isEqualTo("Restaurant");
        assertThat(r.serviceResponsable()).isEqualTo("Jean Mbuyi");
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire()).contains("Service budgétaire : aucun → Restaurant");
        verify(controle, never()).controlerNote(any());   // le controle releve se fera a la soumission
    }

    @Test
    void le_dfin_corrige_le_service_pendant_la_verification_et_le_controle_est_refait() {
        connecte(dfin);
        NoteFrais note = note(StatutNote.SOUMISE, SensTransaction.DECAISSEMENT, RESTAURANT);

        service.modifierService(2L, 2L);

        assertThat(note.getService()).isSameAs(LOGISTIQUE);
        assertThat(note.getStatutBudget()).isEqualTo(StatutControleBudget.CONFORME);
        verify(controle).controlerNote(note);
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire()).contains("Service budgétaire : Restaurant → Logistique");
    }

    @Test
    void pendant_la_verification_seul_le_dfin_change_le_service() {
        connecte(demandeur);
        note(StatutNote.SOUMISE, SensTransaction.DECAISSEMENT, RESTAURANT);
        assertThatThrownBy(() -> service.modifierService(2L, 2L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("Pendant la vérification de la note");
    }

    @Test
    void une_note_validee_ne_change_plus_de_service() {
        connecte(dfin);
        note(StatutNote.VALIDEE_DA, SensTransaction.DECAISSEMENT, RESTAURANT);
        assertThatThrownBy(() -> service.modifierService(2L, 2L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("se choisit avant sa vérification");
    }

    @Test
    void un_autre_utilisateur_ne_change_pas_le_service_d_un_brouillon() {
        connecte(User.builder().id(99L).prenom("Autre").nom("Agent").email("autre").build());
        note(StatutNote.BROUILLON, SensTransaction.DECAISSEMENT, null);
        assertThatThrownBy(() -> service.modifierService(2L, 1L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("Seul le createur");
    }

    @Test
    void une_note_d_encaissement_ne_se_rattache_pas_a_un_service() {
        connecte(demandeur);
        note(StatutNote.BROUILLON, SensTransaction.ENCAISSEMENT, null);
        assertThatThrownBy(() -> service.modifierService(2L, 1L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("encaissement");
    }

    @Test
    void un_service_desactive_ne_se_choisit_plus_mais_reste_sur_les_notes_existantes() {
        connecte(demandeur);
        NoteFrais note = note(StatutNote.BROUILLON, SensTransaction.DECAISSEMENT, null);
        assertThatThrownBy(() -> service.modifierService(2L, 8L)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("« Fermé » est désactivé");
        note.setService(FERME);
        service.modifierService(2L, 8L);   // inchange : accepte
        assertThat(note.getService()).isSameAs(FERME);
        service.modifierService(2L, null);  // retire le service
        assertThat(note.getService()).isNull();
    }
}

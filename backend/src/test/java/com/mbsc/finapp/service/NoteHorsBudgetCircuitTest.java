package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Circuit d'une note non couverte par le budget : elle n'est jamais bloquée à la soumission, elle part chez le DFIN
 * signalée « hors budget », et c'est le DA qui décide en motivant sa validation.
 */
class NoteHorsBudgetCircuitTest {

    private NoteFraisRepository notes;
    private CurrentUserProvider utilisateur;
    private ControleBudgetaireService controle;
    private NotificationService notifications;
    private NoteFraisService service;
    private User demandeur;

    @BeforeEach
    void preparer() throws Exception {
        // NoteFraisService a une vingtaine de dépendances : chacune est simulée, seules celles du circuit sont réglées.
        Constructor<?> constructeur = NoteFraisService.class.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructeur.getParameterTypes()).map(t -> mock(t)).toArray();
        service = (NoteFraisService) constructeur.newInstance(arguments);
        notes = trouver(arguments, NoteFraisRepository.class);
        utilisateur = trouver(arguments, CurrentUserProvider.class);
        controle = trouver(arguments, ControleBudgetaireService.class);
        notifications = trouver(arguments, NotificationService.class);

        demandeur = User.builder().id(5L).prenom("Papy").nom("Penze").email("rest@mbsc.cd").build();
        when(utilisateur.requireUser()).thenReturn(demandeur);
        when(utilisateur.requireUserId()).thenReturn(5L);
        when(notes.saveAndFlush(any(NoteFrais.class))).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private static <T> T trouver(Object[] arguments, Class<T> type) {
        return (T) Arrays.stream(arguments).filter(type::isInstance).findFirst().orElseThrow();
    }

    private NoteFrais note(StatutNote statut, String justification) {
        NoteFrais note = NoteFrais.builder().id(2L).reference("NF-2026-000002").objet("Achat de boissons — 3 produits")
            .montant(new BigDecimal("727000")).devise(Devise.CDF).statut(statut).sens(SensTransaction.DECAISSEMENT)
            .createur(demandeur).justificationBudget(justification).build();
        when(notes.findWithDetailsById(2L)).thenReturn(Optional.of(note));
        return note;
    }

    private void controleRetourne(StatutControleBudget statut, boolean justificationRequise) {
        when(controle.controlerNote(any(NoteFrais.class))).thenReturn(new ControleBudgetaireResponse(
            2026, 10, null, null, null, statut, justificationRequise, "USD", BigDecimal.ONE, List.of(), List.of(), true));
    }

    @Test
    void uneNoteSansBudgetEstSoumiseSansJustificationEtLeDfinEstPrevenu() {
        NoteFrais note = note(StatutNote.BROUILLON, null);
        controleRetourne(StatutControleBudget.SANS_BUDGET, true);

        NoteFraisDetailResponse reponse = service.soumettre(2L, null);     // ne lève plus d'exception

        assertThat(reponse.statut()).isEqualTo(StatutNote.SOUMISE);
        assertThat(note.getStatut()).isEqualTo(StatutNote.SOUMISE);
        assertThat(note.getStatutBudget()).isEqualTo(StatutControleBudget.SANS_BUDGET);
        verify(notifications).notifierRole(eq(RoleType.DFIN), eq(TypeNotification.NOTE_SOUMISE), anyString(),
            contains("(hors budget)"), anyString(), eq(note));
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire())
            .anyMatch(c -> c.contains("Contrôle budgétaire") && c.contains("sans justification du demandeur"));
    }

    @Test
    void laJustificationDuDemandeurEstFacultativeMaisTracee() {
        NoteFrais note = note(StatutNote.BROUILLON, "Urgence : rupture de stock avant le week-end");
        controleRetourne(StatutControleBudget.HORS_BUDGET, true);

        service.soumettre(2L, null);

        assertThat(note.getStatut()).isEqualTo(StatutNote.SOUMISE);
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire())
            .anyMatch(c -> c.contains("justification du demandeur : Urgence : rupture de stock avant le week-end"));
    }

    @Test
    void uneNoteCouverteParLeBudgetNEstPasSignalee() {
        NoteFrais note = note(StatutNote.BROUILLON, null);
        controleRetourne(StatutControleBudget.CONFORME, false);

        service.soumettre(2L, null);

        assertThat(note.getStatut()).isEqualTo(StatutNote.SOUMISE);
        verify(notifications).notifierRole(eq(RoleType.DFIN), eq(TypeNotification.NOTE_SOUMISE), anyString(),
            org.mockito.AdditionalMatchers.not(contains("hors budget")), anyString(), eq(note));
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire()).noneMatch(c -> c.contains("Contrôle budgétaire"));
    }

    @Test
    void leDfinTransmetAuDaLaNoteHorsBudgetAvecLaMention() {
        NoteFrais note = note(StatutNote.SOUMISE, null);
        controleRetourne(StatutControleBudget.HORS_BUDGET, true);

        service.verifier(2L, null);

        assertThat(note.getStatut()).isEqualTo(StatutNote.VERIFIEE_DFIN);
        verify(notifications).notifierRole(eq(RoleType.DA), eq(TypeNotification.NOTE_VERIFIEE), anyString(),
            contains("(hors budget)"), anyString(), eq(note));
    }

    @Test
    void leDaNePeutValiderUneNoteHorsBudgetQueEnMotivantSaDecision() {
        NoteFrais note = note(StatutNote.VERIFIEE_DFIN, null);
        controleRetourne(StatutControleBudget.SANS_BUDGET, true);

        assertThatThrownBy(() -> service.valider(2L, null)).hasMessageContaining("motivez votre validation");
        assertThatThrownBy(() -> service.valider(2L, new ActionWorkflowRequest("   ")))
            .hasMessageContaining("motivez votre validation");
        assertThat(note.getStatut()).isEqualTo(StatutNote.VERIFIEE_DFIN);
        verify(notifications, never()).notifierRole(eq(RoleType.DFIN), eq(TypeNotification.NOTE_VALIDEE), anyString(), anyString(), anyString(), any());

        service.valider(2L, new ActionWorkflowRequest("Budget 2026 pas encore approuvé ; achat indispensable, validé par la direction"));

        assertThat(note.getStatut()).isEqualTo(StatutNote.VALIDEE_DA);
        assertThat(note.getObservations()).extracting(o -> o.getCommentaire())
            .anyMatch(c -> c.startsWith("Validee hors budget : Budget 2026 pas encore approuvé"));
    }

    @Test
    void leDaValideSansMotifUneNoteCouverteParLeBudget() {
        NoteFrais note = note(StatutNote.VERIFIEE_DFIN, null);
        controleRetourne(StatutControleBudget.CONFORME, false);

        service.valider(2L, null);

        assertThat(note.getStatut()).isEqualTo(StatutNote.VALIDEE_DA);
    }
}

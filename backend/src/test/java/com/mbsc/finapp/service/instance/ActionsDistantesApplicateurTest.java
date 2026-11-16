package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.instance.ActionDistanteTransfert;
import com.mbsc.finapp.dto.instance.ResultatActionDistante;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.UserPrincipal;
import com.mbsc.finapp.service.AdminService;
import com.mbsc.finapp.service.NoteFraisService;
import com.mbsc.finapp.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application, par le serveur local, des actions faites sur la copie en ligne : au nom de leur auteur, par les memes
 * methodes qu'au guichet, une seule fois, et refusees si la note a change entre-temps.
 */
class ActionsDistantesApplicateurTest {

    private final NoteFraisService notes = mock(NoteFraisService.class);
    private final NoteFraisRepository noteRepository = mock(NoteFraisRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminService admin = mock(AdminService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ActionsDistantesApplicateur applicateur = new ActionsDistantesApplicateur(mock(ReplicationClient.class), notes,
        noteRepository, userRepository, admin, notifications, audit, jdbc, json, mock(PlatformTransactionManager.class));

    private final Instant majVue = Instant.parse("2026-10-24T08:15:30.123456Z");
    private User da;
    private NoteFrais note;

    @BeforeEach
    void preparer() {
        da = User.builder().id(4L).email("da").actif(true)
            .roles(Set.of(Role.builder().nom(RoleType.DA).build())).build();
        note = NoteFrais.builder().id(12L).reference("NF-2026-000012").statut(StatutNote.VERIFIEE_DFIN).build();
        note.setDateMaj(majVue);
        when(userRepository.findById(4L)).thenReturn(Optional.of(da));
        when(noteRepository.findById(12L)).thenReturn(Optional.of(note));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    @AfterEach
    void vider() {
        SecurityContextHolder.clearContext();
    }

    private ActionDistanteTransfert validation(String statutVu, Instant majVueParLAuteur) {
        return new ActionDistanteTransfert("6f1c2a64-58c6-4cb5-9d55-8a6f3b2c1e01", "VALIDER", 12L, null,
            json.createObjectNode().put("commentaire", "Validé depuis le terrain"), List.of(), 4L, "da", statutVu,
            majVueParLAuteur, "Valider — NF-2026-000012", Instant.parse("2026-10-24T08:20:00Z"));
    }

    @Test
    void une_validation_sur_une_note_inchangee_est_appliquee_au_nom_de_son_auteur() {
        String[] auteurPendantLAction = {null};
        when(notes.valider(eq(12L), any(ActionWorkflowRequest.class))).thenAnswer(i -> {
            auteurPendantLAction[0] = ((UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .getUsername();
            return NoteFraisDetailResponse.from(note);
        });

        ResultatActionDistante r = applicateur.appliquer(validation("VERIFIEE_DFIN", majVue));

        assertThat(r.statut()).isEqualTo("APPLIQUEE");
        assertThat(auteurPendantLAction[0]).isEqualTo("da");
        verify(notes).valider(eq(12L), any(ActionWorkflowRequest.class));
        verify(notifications).notifierUtilisateur(eq(da), eq(TypeNotification.ACTION_EN_LIGNE_APPLIQUEE), anyString(), anyString(),
            eq("/notes-frais/12"), eq(note));
        verify(audit).enregistrer(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("contexte vide ensuite").isNull();
    }

    @Test
    void une_note_dont_le_statut_a_change_n_est_pas_touchee() {
        ResultatActionDistante r = applicateur.appliquer(validation("SOUMISE", majVue));

        assertThat(r.statut()).isEqualTo("REFUSEE");
        assertThat(r.message()).contains("a changé").contains("VERIFIEE_DFIN");
        verify(notes, never()).valider(any(), any());
        verify(notifications).notifierUtilisateur(eq(da), eq(TypeNotification.ACTION_EN_LIGNE_REFUSEE), anyString(), anyString(),
            anyString(), eq(note));
    }

    @Test
    void une_note_modifiee_depuis_sans_changer_de_statut_n_est_pas_touchee() {
        ResultatActionDistante r = applicateur.appliquer(validation("VERIFIEE_DFIN", majVue.minusSeconds(60)));

        assertThat(r.statut()).isEqualTo("REFUSEE");
        verify(notes, never()).valider(any(), any());
    }

    @Test
    void un_refus_des_droits_du_serveur_principal_est_explique() {
        when(notes.valider(eq(12L), any())).thenThrow(new AccessDeniedException("Access Denied"));

        ResultatActionDistante r = applicateur.appliquer(validation("VERIFIEE_DFIN", majVue));

        assertThat(r.statut()).isEqualTo("REFUSEE");
        assertThat(r.message()).contains("Droits insuffisants");
    }

    @Test
    void un_auteur_desactive_entre_temps_ne_peut_plus_agir() {
        da.setActif(false);

        ResultatActionDistante r = applicateur.appliquer(validation("VERIFIEE_DFIN", majVue));

        assertThat(r.statut()).isEqualTo("REFUSEE");
        assertThat(r.message()).contains("désactivé");
        verify(notes, never()).valider(any(), any());
    }

    @Test
    void une_action_deja_appliquee_ne_l_est_pas_deux_fois() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(
            new ResultatActionDistante("6f1c2a64-58c6-4cb5-9d55-8a6f3b2c1e01", "APPLIQUEE", "déjà fait", 12L)));

        ResultatActionDistante r = applicateur.appliquer(validation("VERIFIEE_DFIN", majVue));

        assertThat(r.message()).isEqualTo("déjà fait");
        verify(notes, never()).valider(any(), any());
        verify(notifications, never()).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void une_desactivation_faite_en_ligne_fixe_l_etat_voulu() {
        ActionDistanteTransfert a = new ActionDistanteTransfert("7a2d3b75-69d7-4dc6-8e66-9b7f4c3d2f02", "ACTIVER_UTILISATEUR",
            null, 9L, json.createObjectNode().put("actif", false), List.of(), 4L, "admin", null, null,
            "Désactivation du compte x", Instant.now());
        when(userRepository.findById(4L)).thenReturn(Optional.of(User.builder().id(4L).email("admin").actif(true)
            .roles(Set.of(Role.builder().nom(RoleType.ADMIN).build())).build()));

        ResultatActionDistante r = applicateur.appliquer(a);

        assertThat(r.statut()).isEqualTo("APPLIQUEE");
        verify(admin).definirActif(9L, false);
        verify(notifications).notifierUtilisateur(any(), eq(TypeNotification.ACTION_EN_LIGNE_APPLIQUEE), anyString(), anyString(),
            isNull(), isNull());
    }
}

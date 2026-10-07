package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutNote;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regle de visibilite des notes de frais, et texte qui l'explique a
 * l'utilisateur. Le test d'equivalence compare la regle extraite dans
 * {@link RegleVisibiliteNote} a l'implementation d'origine, recopiee ci-dessous
 * telle quelle, pour TOUTES les combinaisons de roles : l'extraction ne change
 * pas qui voit quoi.
 */
class RegleVisibiliteNoteTest {

    private static final List<String> AUTORITES = Arrays.stream(RoleType.values())
        .map(r -> "ROLE_" + r.name()).toList();

    private static final Long MOI = 7L;

    private static Set<String> roles(String... noms) {
        Set<String> autorites = new HashSet<>();
        for (String nom : noms) {
            autorites.add("ROLE_" + nom);
        }
        return autorites;
    }

    private static NoteFrais note(StatutNote statut, Long createurId) {
        User createur = createurId == null ? null : User.builder().id(createurId).build();
        return NoteFrais.builder().statut(statut).createur(createur).build();
    }

    // ---------------------------------------------------------------------
    // Equivalence avec l'implementation d'origine
    // ---------------------------------------------------------------------

    /** NoteFraisService.estVisiblePourUtilisateur avant l'extraction, sur des autorites (noms de roles) plutot que le contexte Spring. */
    private static boolean ancienneRegle(Set<String> autorites, NoteFrais note, Long utilisateurId) {
        boolean estDA = autorites.stream().anyMatch(a -> "ROLE_DA".equals(a));
        boolean estCaissier = autorites.stream().anyMatch(a -> "ROLE_CAISSIER".equals(a));
        boolean estDfinOuAdmin = autorites.stream()
            .anyMatch(a -> "ROLE_DFIN".equals(a) || "ROLE_ADMIN".equals(a));
        boolean estDG = autorites.stream().anyMatch(a -> "ROLE_DG".equals(a));
        boolean estDirecteurSeul = autorites.stream().anyMatch(a -> "ROLE_DIRECTEUR".equals(a))
            && autorites.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_DG".equals(a) || "ROLE_DA".equals(a)
                         || "ROLE_DFIN".equals(a) || "ROLE_CAISSIER".equals(a));
        boolean estCaissierSeul = estCaissier && !estDfinOuAdmin && !estDA && !estDG;
        boolean estLogistiqueSeul = autorites.stream().anyMatch(a -> "ROLE_LOGISTIQUE".equals(a))
            && autorites.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_DG".equals(a) || "ROLE_DA".equals(a)
                         || "ROLE_DFIN".equals(a) || "ROLE_CAISSIER".equals(a));
        boolean estRespRestaurantSeul = autorites.stream().anyMatch(a -> "ROLE_RESP_RESTAURANT".equals(a))
            && autorites.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a) || "ROLE_DG".equals(a) || "ROLE_DA".equals(a)
                         || "ROLE_DFIN".equals(a) || "ROLE_CAISSIER".equals(a) || "ROLE_COMPTABLE".equals(a));

        if (estDirecteurSeul || estRespRestaurantSeul || estLogistiqueSeul) {
            return note.getCreateur() != null && note.getCreateur().getId().equals(utilisateurId);
        }
        if (estCaissierSeul) {
            boolean estCreateur = note.getCreateur() != null && note.getCreateur().getId().equals(utilisateurId);
            return estCreateur || EnumSet.of(StatutNote.VALIDEE_DA, StatutNote.TRANSMISE_CAISSE, StatutNote.PAYEE)
                .contains(note.getStatut());
        }
        if (estDA && !estDfinOuAdmin) {
            return note.getStatut() != StatutNote.BROUILLON && note.getStatut() != StatutNote.SOUMISE;
        }
        return true;
    }

    @Test
    void la_regle_extraite_donne_les_memes_resultats_que_l_originale_pour_toutes_les_combinaisons_de_roles() {
        Long[] createurs = { MOI, 8L, null };
        int comparaisons = 0;
        Set<RegleVisibiliteNote> reglesAtteintes = EnumSet.noneOf(RegleVisibiliteNote.class);

        for (int masque = 0; masque < (1 << AUTORITES.size()); masque++) {
            Set<String> autorites = new HashSet<>();
            for (int i = 0; i < AUTORITES.size(); i++) {
                if ((masque & (1 << i)) != 0) {
                    autorites.add(AUTORITES.get(i));
                }
            }
            RegleVisibiliteNote regle = RegleVisibiliteNote.pour(autorites);
            reglesAtteintes.add(regle);
            for (StatutNote statut : StatutNote.values()) {
                for (Long createurId : createurs) {
                    NoteFrais note = note(statut, createurId);
                    assertThat(regle.voit(note, MOI))
                        .as("roles=%s statut=%s createur=%s", autorites, statut, createurId)
                        .isEqualTo(ancienneRegle(autorites, note, MOI));
                    comparaisons++;
                }
            }
        }

        assertThat(comparaisons).isEqualTo((1 << AUTORITES.size()) * StatutNote.values().length * createurs.length);
        // Le test n'est pas vide de sens : les quatre regles sont bien exercees.
        assertThat(reglesAtteintes).containsExactlyInAnyOrder(RegleVisibiliteNote.values());
    }

    // ---------------------------------------------------------------------
    // Cas lisibles : quelle regle pour quel profil, et ce qu'elle laisse voir
    // ---------------------------------------------------------------------

    @Test
    void directeur_responsable_restaurant_et_logistique_seuls_ne_voient_que_leurs_notes() {
        for (String role : List.of("DIRECTEUR", "RESP_RESTAURANT", "LOGISTIQUE")) {
            RegleVisibiliteNote regle = RegleVisibiliteNote.pour(roles(role));

            assertThat(regle).as(role).isEqualTo(RegleVisibiliteNote.SES_NOTES);
            assertThat(regle.voit(note(StatutNote.BROUILLON, MOI), MOI)).as(role).isTrue();
            assertThat(regle.voit(note(StatutNote.PAYEE, 8L), MOI)).as(role).isFalse();
            assertThat(regle.voit(note(StatutNote.PAYEE, null), MOI)).as(role).isFalse();
        }
    }

    @Test
    void un_role_de_la_chaine_de_validation_leve_la_restriction() {
        assertThat(RegleVisibiliteNote.pour(roles("DIRECTEUR", "DFIN"))).isEqualTo(RegleVisibiliteNote.TOUTES);
        assertThat(RegleVisibiliteNote.pour(roles("RESP_RESTAURANT", "COMPTABLE"))).isEqualTo(RegleVisibiliteNote.TOUTES);
        assertThat(RegleVisibiliteNote.pour(roles("LOGISTIQUE", "ADMIN"))).isEqualTo(RegleVisibiliteNote.TOUTES);
    }

    @Test
    void le_caissier_voit_ses_notes_et_celles_deja_validees_par_le_da() {
        RegleVisibiliteNote regle = RegleVisibiliteNote.pour(roles("CAISSIER"));

        assertThat(regle).isEqualTo(RegleVisibiliteNote.CAISSIER);
        assertThat(regle.voit(note(StatutNote.BROUILLON, MOI), MOI)).isTrue();
        assertThat(regle.voit(note(StatutNote.VALIDEE_DA, 8L), MOI)).isTrue();
        assertThat(regle.voit(note(StatutNote.TRANSMISE_CAISSE, 8L), MOI)).isTrue();
        assertThat(regle.voit(note(StatutNote.PAYEE, 8L), MOI)).isTrue();
        assertThat(regle.voit(note(StatutNote.SOUMISE, 8L), MOI)).isFalse();
        assertThat(regle.voit(note(StatutNote.VERIFIEE_DFIN, 8L), MOI)).isFalse();
    }

    @Test
    void le_da_ne_voit_pas_les_notes_en_brouillon_ni_soumises() {
        RegleVisibiliteNote regle = RegleVisibiliteNote.pour(roles("DA"));

        assertThat(regle).isEqualTo(RegleVisibiliteNote.DA);
        assertThat(regle.voit(note(StatutNote.BROUILLON, 8L), MOI)).isFalse();
        assertThat(regle.voit(note(StatutNote.SOUMISE, 8L), MOI)).isFalse();
        assertThat(regle.voit(note(StatutNote.VERIFIEE_DFIN, 8L), MOI)).isTrue();
        // Ses propres brouillons non plus : la regle ne depend pas du createur.
        assertThat(regle.voit(note(StatutNote.BROUILLON, MOI), MOI)).isFalse();
        // Un DA qui est aussi caissier suit la regle du DA.
        assertThat(RegleVisibiliteNote.pour(roles("DA", "CAISSIER"))).isEqualTo(RegleVisibiliteNote.DA);
    }

    @Test
    void dfin_admin_dg_et_les_autres_roles_voient_toutes_les_notes() {
        for (String role : List.of("DFIN", "ADMIN", "DG", "COMPTABLE", "GEST_PATRIMOINE")) {
            RegleVisibiliteNote regle = RegleVisibiliteNote.pour(roles(role));

            assertThat(regle).as(role).isEqualTo(RegleVisibiliteNote.TOUTES);
            for (StatutNote statut : StatutNote.values()) {
                assertThat(regle.voit(note(statut, 8L), MOI)).as(role + " " + statut).isTrue();
            }
        }
        assertThat(RegleVisibiliteNote.pour(Set.of())).isEqualTo(RegleVisibiliteNote.TOUTES);
    }

    // ---------------------------------------------------------------------
    // Texte destine a l'utilisateur
    // ---------------------------------------------------------------------

    @Test
    void seule_la_vue_complete_n_a_rien_a_expliquer() {
        for (RegleVisibiliteNote regle : RegleVisibiliteNote.values()) {
            assertThat(regle.estRestreinte()).as(regle.name()).isEqualTo(regle != RegleVisibiliteNote.TOUTES);
        }
    }

    /** Le texte doit dire a l'utilisateur qu'une note absente peut tenir a un manque d'autorisation. */
    @Test
    void chaque_regle_restrictive_explique_qu_une_note_peut_manquer_faute_d_autorisation() {
        for (RegleVisibiliteNote regle : RegleVisibiliteNote.values()) {
            assertThat(regle.explication()).as(regle.name()).isNotBlank();
            if (regle.estRestreinte()) {
                assertThat(regle.explication()).as(regle.name()).contains("autorisé");
            }
        }
        assertThat(RegleVisibiliteNote.SES_NOTES.explication()).contains("créées vous-même");
        assertThat(RegleVisibiliteNote.CAISSIER.explication()).contains("validées par le DA");
        assertThat(RegleVisibiliteNote.DA.explication()).contains("brouillon").contains("DFIN");
    }
}

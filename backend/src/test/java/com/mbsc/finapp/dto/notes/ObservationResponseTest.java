package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutNote;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Historique du circuit d'une note : la fonction de l'auteur accompagne son nom, afin que le texte
 * de l'étape n'ait plus à citer le rôle (« Validee » et non « Validee par le DA »).
 */
class ObservationResponseTest {

    private static User utilisateur(String prenom, String nom, String fonction) {
        return User.builder().prenom(prenom).nom(nom).fonction(fonction).build();
    }

    private static ObservationNote etape(User auteur, StatutNote statut, String texte) {
        return ObservationNote.builder().auteur(auteur).statutAuMoment(statut).commentaire(texte).build();
    }

    @Test
    void la_fonction_de_l_auteur_accompagne_son_nom() {
        ObservationResponse r = ObservationResponse.from(
            etape(utilisateur("Alice", "Martin", "Directeur Financier"), StatutNote.VERIFIEE_DFIN, "Verifiee"));

        assertThat(r.auteurNom()).isEqualTo("Alice Martin");
        assertThat(r.auteurFonction()).isEqualTo("Directeur Financier");
        assertThat(r.commentaire()).isEqualTo("Verifiee");
        assertThat(r.statutAuMoment()).isEqualTo(StatutNote.VERIFIEE_DFIN);
    }

    @Test
    void la_fonction_est_debarrassee_de_ses_espaces() {
        assertThat(ObservationResponse.from(etape(utilisateur("Paul", "Durand", "  Caissier \t"), StatutNote.PAYEE, "x"))
            .auteurFonction()).isEqualTo("Caissier");
    }

    @Test
    void une_fonction_non_renseignee_reste_vide() {
        for (String vide : new String[] {null, "", "   "}) {
            assertThat(ObservationResponse.from(etape(utilisateur("Systeme", "Admin", vide), StatutNote.SOUMISE, "x"))
                .auteurFonction()).as("fonction « %s »", vide).isNull();
        }
    }

    @Test
    void une_etape_sans_auteur_n_a_ni_nom_ni_fonction() {
        ObservationResponse r = ObservationResponse.from(etape(null, StatutNote.BROUILLON, "Creation de la note"));

        assertThat(r.auteurNom()).isNull();
        assertThat(r.auteurFonction()).isNull();
    }

    @Test
    void la_fiche_de_la_note_porte_la_fonction_du_demandeur_et_celle_de_chaque_auteur() {
        User gerant = utilisateur("Claire", "Petit", "Gerant");
        User dfin = utilisateur("Alice", "Martin", "Directeur Financier");
        NoteFrais note = NoteFrais.builder().createur(gerant).statut(StatutNote.VERIFIEE_DFIN).build();
        note.addObservation(etape(gerant, StatutNote.BROUILLON, "Creation de la note"));
        note.addObservation(etape(dfin, StatutNote.VERIFIEE_DFIN, "Verifiee"));

        NoteFraisDetailResponse r = NoteFraisDetailResponse.from(note);

        assertThat(r.demandeurNom()).isEqualTo("Claire Petit");
        assertThat(r.demandeurFonction()).isEqualTo("Gerant");
        assertThat(r.observations()).extracting(ObservationResponse::auteurFonction)
            .containsExactly("Gerant", "Directeur Financier");
    }

    @Test
    void un_demandeur_sans_fonction_n_en_affiche_pas() {
        NoteFrais note = NoteFrais.builder().createur(utilisateur("Systeme", "Admin", null)).build();

        assertThat(NoteFraisDetailResponse.from(note).demandeurFonction()).isNull();
    }
}

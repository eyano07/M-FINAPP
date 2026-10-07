package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche d'une note de frais par reference, libelle (objet) ou compte
 * d'une de ses lignes : fragments, sans casse ni accents, criteres combines.
 */
class RechercheNoteFraisTest {

    private static CompteOHADA compte(String numero, String libelle) {
        return CompteOHADA.builder().numero(numero).libelle(libelle).build();
    }

    private static LigneNoteFrais ligne(CompteOHADA compte) {
        return LigneNoteFrais.builder().montant(BigDecimal.TEN).compteImputation(compte).build();
    }

    /** Une mission a deux lignes : assurance du vehicule et carburant. */
    private static NoteFrais mission() {
        NoteFrais note = NoteFrais.builder()
            .reference("NF-2026-000123")
            .objet("Mission terrain à Kolwezi — déplacement")
            .build();
        note.addLigne(ligne(compte("6252", "Assurances matériel de transport")));
        note.addLigne(ligne(compte("6042", "Matières combustibles")));
        return note;
    }

    @Test
    void la_reference_se_cherche_par_fragment_sans_tenir_compte_de_la_casse() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de("NF-2026-000123", null, null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("nf-2026-000123", null, null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("000123", null, null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("123", null, null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("NF-2025", null, null).correspond(note)).isFalse();
        assertThat(RechercheNoteFrais.de("000124", null, null).correspond(note)).isFalse();
    }

    @Test
    void le_libelle_ignore_la_casse_et_les_accents() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de(null, "terrain", null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, "DEPLACEMENT", null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, "déplacement", null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, "a kolwezi", null).correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, "achat", null).correspond(note)).isFalse();
    }

    @Test
    void le_compte_se_cherche_par_son_nom_sur_n_importe_quelle_ligne() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de(null, null, "assurances").correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, null, "COMBUSTIBLES").correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, null, "materiel de transport").correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, null, "loyer").correspond(note)).isFalse();
    }

    @Test
    void le_compte_se_cherche_aussi_par_le_debut_de_son_numero() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de(null, null, "6252").correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de(null, null, "604").correspond(note)).isTrue();
        // Debut de numero seulement : « 252 » n'est pas le debut de 6252.
        assertThat(RechercheNoteFrais.de(null, null, "252").correspond(note)).isFalse();
    }

    /** Le libelle de la note n'est pas celui du compte : chaque critere regarde son propre champ. */
    @Test
    void le_critere_compte_ne_regarde_pas_le_libelle_de_la_note() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de(null, null, "terrain").correspond(note)).isFalse();
        assertThat(RechercheNoteFrais.de(null, "assurances", null).correspond(note)).isFalse();
    }

    @Test
    void une_ligne_sans_compte_ne_correspond_a_aucune_recherche_de_compte() {
        NoteFrais note = NoteFrais.builder().reference("NF-2026-000001").objet("Frais divers").build();
        note.addLigne(ligne(null));

        assertThat(RechercheNoteFrais.de(null, null, "divers").correspond(note)).isFalse();
        // Sans critere de compte, la note reste trouvable par son libelle.
        assertThat(RechercheNoteFrais.de(null, "divers", null).correspond(note)).isTrue();
    }

    @Test
    void les_criteres_se_combinent_en_et() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de("123", "terrain", "combustibles").correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("123", "terrain", "loyer").correspond(note)).isFalse();
        assertThat(RechercheNoteFrais.de("999", "terrain", "combustibles").correspond(note)).isFalse();
        assertThat(RechercheNoteFrais.de("123", "achat", "combustibles").correspond(note)).isFalse();
    }

    @Test
    void un_critere_vide_ou_blanc_est_ignore() {
        NoteFrais note = mission();

        RechercheNoteFrais aucune = RechercheNoteFrais.de("  ", "", null);

        assertThat(aucune.estVide()).isTrue();
        assertThat(aucune.correspond(note)).isTrue();
        assertThat(RechercheNoteFrais.de("123", "  ", "").estVide()).isFalse();
    }

    @Test
    void les_espaces_de_tete_et_de_queue_sont_ignores() {
        NoteFrais note = mission();

        assertThat(RechercheNoteFrais.de("  000123  ", " terrain ", " assurances ").correspond(note)).isTrue();
    }

    @Test
    void une_note_sans_ligne_ne_correspond_a_aucune_recherche_de_compte() {
        NoteFrais note = NoteFrais.builder().reference("NF-2026-000002").objet("Sans ligne").build();

        assertThat(RechercheNoteFrais.de(null, null, "carburant").correspond(note)).isFalse();
        assertThat(RechercheNoteFrais.de(null, "sans", null).correspond(note)).isTrue();
    }
}

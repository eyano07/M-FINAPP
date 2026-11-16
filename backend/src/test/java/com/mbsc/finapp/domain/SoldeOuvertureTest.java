package com.mbsc.finapp.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Règle du 13/11/2026 : une pièce du journal datée du 1er janvier est un solde d'ouverture, et son libellé le dit.
 * Seuls les comptes de bilan (classes 1 à 5) ont un solde d'ouverture en SYSCOHADA.
 */
class SoldeOuvertureTest {

    private static final LocalDate PREMIER_JANVIER = LocalDate.of(2026, 1, 1);

    @Test
    void une_piece_du_1er_janvier_aux_seuls_comptes_de_bilan_est_un_solde_d_ouverture() {
        assertThat(SoldeOuverture.concerne(PREMIER_JANVIER, List.of("1011", "5215", "571", "120111", "212891", "46211")))
            .isTrue();
        assertThat(SoldeOuverture.concerne(LocalDate.of(2027, 1, 1), List.of("2441", "4011"))).isTrue();
    }

    @Test
    void une_charge_ou_un_produit_du_1er_janvier_reste_un_mouvement() {
        assertThat(SoldeOuverture.concerne(PREMIER_JANVIER, List.of("6222", "571"))).isFalse();
        assertThat(SoldeOuverture.concerne(PREMIER_JANVIER, List.of("4111", "7061"))).isFalse();
        assertThat(SoldeOuverture.concerne(PREMIER_JANVIER, List.of("8110", "2441"))).isFalse();
    }

    @Test
    void les_autres_jours_ne_sont_pas_des_jours_d_ouverture() {
        assertThat(SoldeOuverture.concerne(LocalDate.of(2026, 1, 2), List.of("2441", "4011"))).isFalse();
        assertThat(SoldeOuverture.concerne(LocalDate.of(2026, 12, 31), List.of("2441", "4011"))).isFalse();
        assertThat(SoldeOuverture.concerne(null, List.of("2441", "4011"))).isFalse();
        assertThat(SoldeOuverture.concerne(PREMIER_JANVIER, List.of())).isFalse();
    }

    @Test
    void le_libelle_d_une_ecriture_d_ouverture_porte_la_mention() {
        assertThat(SoldeOuverture.libelle("Capital souscrit, non appelé", true))
            .isEqualTo("Capital souscrit, non appelé - Solde d'ouverture");
        assertThat(SoldeOuverture.libelle("  Banque — Equity Bank ", true)).isEqualTo("Banque — Equity Bank - Solde d'ouverture");
        assertThat(SoldeOuverture.libelle(null, true)).isEqualTo("Solde d'ouverture");
        assertThat(SoldeOuverture.libelle(" ", true)).isEqualTo("Solde d'ouverture");
    }

    @Test
    void la_mention_ne_se_repete_pas_et_ne_touche_pas_les_mouvements() {
        assertThat(SoldeOuverture.libelle("Solde d'ouverture", true)).isEqualTo("Solde d'ouverture");
        assertThat(SoldeOuverture.libelle("Caisse - Solde d'ouverture", true)).isEqualTo("Caisse - Solde d'ouverture");
        assertThat(SoldeOuverture.libelle("Caisse - solde d’ouverture", true)).isEqualTo("Caisse - solde d’ouverture");
        assertThat(SoldeOuverture.libelle("Loyer de janvier", false)).isEqualTo("Loyer de janvier");
        assertThat(SoldeOuverture.libelle(null, false)).isNull();
    }

    @Test
    void la_mention_d_une_ecriture_suit_le_marqueur_de_sa_piece() {
        PieceComptable ouverture = PieceComptable.builder().soldeOuverture(true).build();
        EcritureGrandLivre ligne = EcritureGrandLivre.builder().libelle("Mobilier de bureau").piece(ouverture).build();
        assertThat(SoldeOuverture.libelle(ligne)).isEqualTo("Mobilier de bureau - Solde d'ouverture");

        ouverture.setSoldeOuverture(false);
        assertThat(SoldeOuverture.libelle(ligne)).isEqualTo("Mobilier de bureau");
        assertThat(SoldeOuverture.libelle(EcritureGrandLivre.builder().libelle("Sans pièce").build())).isEqualTo("Sans pièce");
    }
}

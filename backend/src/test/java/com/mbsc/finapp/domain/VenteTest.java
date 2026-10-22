package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.StatutVente;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Une vente n'est « réglée » que si elle est validée et que sa créance, s'il y en a une, est soldée. */
class VenteTest {

    private static Vente vente(StatutVente statut, ModeReglement mode, PieceComptable pieceReglement) {
        return Vente.builder().statut(statut).modeReglement(mode).pieceReglement(pieceReglement).build();
    }

    @Test
    void unBrouillonNEstJamaisRegle() {
        // Rien n'est comptabilisé tant que la vente n'est pas validée : le client n'a pas payé, quel que soit le mode.
        assertThat(vente(StatutVente.BROUILLON, ModeReglement.CAISSE, null).estReglee()).isFalse();
        assertThat(vente(StatutVente.BROUILLON, ModeReglement.BANQUE, null).estReglee()).isFalse();
        assertThat(vente(StatutVente.BROUILLON, ModeReglement.MOBILE_MONEY, null).estReglee()).isFalse();
        assertThat(vente(StatutVente.BROUILLON, ModeReglement.CREDIT, null).estReglee()).isFalse();
    }

    @Test
    void uneVenteValideeAuComptantEstReglee() {
        assertThat(vente(StatutVente.VALIDEE, ModeReglement.CAISSE, null).estReglee()).isTrue();
        assertThat(vente(StatutVente.VALIDEE, ModeReglement.BANQUE, null).estReglee()).isTrue();
        assertThat(vente(StatutVente.VALIDEE, ModeReglement.MOBILE_MONEY, null).estReglee()).isTrue();
    }

    @Test
    void uneVenteSansClientPorteUnTiret() {
        // Demande de l'utilisateur : plus de « Client comptoir », un simple « - » dans les libellés, la facture et le journal.
        assertThat(Vente.builder().build().designationClient()).isEqualTo("-");
        assertThat(Vente.builder().clientNom("   ").build().designationClient()).isEqualTo("-");
        assertThat(Vente.builder().clientNom(" Jean Mukendi ").build().designationClient()).isEqualTo("Jean Mukendi");
        assertThat(Vente.builder().client(Client.builder().nom("Société Kasaï").build()).clientNom("ignoré").build()
            .designationClient()).isEqualTo("Société Kasaï");
    }

    @Test
    void uneCreanceValideeResteAPayerJusquAuReglement() {
        assertThat(vente(StatutVente.VALIDEE, ModeReglement.CREDIT, null).estReglee()).isFalse();
        assertThat(vente(StatutVente.VALIDEE, ModeReglement.CREDIT, new PieceComptable()).estReglee()).isTrue();
    }
}

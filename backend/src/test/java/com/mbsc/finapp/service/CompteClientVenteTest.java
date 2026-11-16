package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Client;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.Vente;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Ventes à crédit sur le compte propre du client (V145, demande du 15/11/2026). */
class CompteClientVenteTest {

    private static Client client(String compte) {
        return Client.builder().code("CLI-0001").nom("Essai").compteComptable(compte).build();
    }

    private static PieceComptable pieceDebitant(String numero) {
        PieceComptable p = PieceComptable.builder().reference("PC-1").build();
        p.getLignes().add(EcritureGrandLivre.builder()
            .compte(CompteOHADA.builder().numero(numero).libelle("x").build())
            .debit(new BigDecimal("100")).credit(BigDecimal.ZERO).build());
        p.getLignes().add(EcritureGrandLivre.builder()
            .compte(CompteOHADA.builder().numero("7011").libelle("y").build())
            .debit(BigDecimal.ZERO).credit(new BigDecimal("100")).build());
        return p;
    }

    @Test
    void nouvelleVenteSurLeCompteDuClient() {
        Vente v = Vente.builder().client(client("41112")).build();
        assertThat(VenteService.compteClient(v)).isEqualTo("41112");
    }

    @Test
    void sansFicheOuSansCompteLeCollectif() {
        assertThat(VenteService.compteClient(Vente.builder().build())).isEqualTo("4111");
        assertThat(VenteService.compteClient(Vente.builder().client(client(null)).build())).isEqualTo("4111");
    }

    @Test
    void venteAncienneRestesurLeCompteQueSaPieceADebite() {
        // Vente passée sur 4111 avant que son client reçoive le compte 41112 : son règlement vise 4111.
        Vente v = Vente.builder().client(client("41112")).piece(pieceDebitant("4111")).build();
        assertThat(VenteService.compteCreance(v)).isEqualTo("4111");
        Vente w = Vente.builder().client(client("41112")).piece(pieceDebitant("41112")).build();
        assertThat(VenteService.compteCreance(w)).isEqualTo("41112");
    }
}

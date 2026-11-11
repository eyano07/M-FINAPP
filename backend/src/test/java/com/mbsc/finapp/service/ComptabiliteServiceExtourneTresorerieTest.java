package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.ReleveBancaireRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extourne d'une piece de tresorerie sans operation derriere elle (journal importe, piece en double) : le DFIN seul la
 * contre-passe, comme le veut le SYSCOHADA revise (la piece reste, ANNULEE, son extourne la solde). Une piece adossee a
 * une vraie operation de caisse reste refusee a tous : elle se corrige par une note de frais.
 */
class ComptabiliteServiceExtourneTresorerieTest {

    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final ComptabiliteService service = new ComptabiliteService(pieces, ecritures,
        mock(CompteOHADARepository.class), mock(MouvementStockRepository.class), mock(VenteRepository.class),
        mock(ReferenceGenerator.class), utilisateur, mock(PeriodeComptableService.class),
        new ConversionDeviseService(mock(com.mbsc.finapp.repository.TauxChangeRepository.class)),
        mock(ReleveBancaireRepository.class));

    private PieceComptable piece;

    @BeforeEach
    void preparer() {
        piece = PieceComptable.builder().reference("PC-2026-002124").libelle("Reglement en double")
            .datePiece(LocalDate.of(2026, 11, 5)).statut(StatutPiece.COMPTABILISEE)
            .totalDebit(new BigDecimal("1000")).totalCredit(new BigDecimal("1000")).build();
        piece.addLigne(ligne("6011", "1000", "0"));
        piece.addLigne(ligne("5711", "0", "1000"));
        when(pieces.findWithLignesById(7L)).thenReturn(Optional.of(piece));
        when(utilisateur.requireUser()).thenReturn(mock(User.class));
    }

    private static EcritureGrandLivre ligne(String compte, String debit, String credit) {
        return EcritureGrandLivre.builder().compte(CompteOHADA.builder().numero(compte).libelle(compte).build())
            .debit(new BigDecimal(debit)).credit(new BigDecimal(credit)).build();
    }

    private void connecte(String role) {
        UserPrincipal principal = mock(UserPrincipal.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_" + role))).when(principal).getAuthorities();
        when(utilisateur.requirePrincipal()).thenReturn(principal);
    }

    @Test
    void le_dfin_extourne_une_piece_de_tresorerie_importee() {
        connecte("DFIN");

        service.annuler(7L);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.ANNULEE);
        verify(pieces).save(any(PieceComptable.class));
    }

    @Test
    void le_comptable_ne_l_extourne_pas() {
        connecte("COMPTABLE");

        assertThatThrownBy(() -> service.annuler(7L)).hasMessageContaining("mouvemente la trésorerie");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
        verify(pieces, never()).save(any(PieceComptable.class));
    }

    @Test
    void une_piece_adossee_a_une_operation_de_caisse_reste_refusee_au_dfin() {
        connecte("DFIN");
        when(ecritures.existsTresorerieParPieceId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.annuler(7L)).hasMessageContaining("opération de caisse");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.comptabilite.LigneEcritureRequest;
import com.mbsc.finapp.dto.comptabilite.PieceCreateRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comptabilisation d'un brouillon (audit SYSCOHADA du 10/11/2026) : validation par une autre personne que l'auteur
 * (C-06), refus d'une date clôturée (C-26), numéro définitif attribué à la validation dans l'exercice de la pièce (C-05),
 * trace de qui a validé et événement au journal (C-09).
 */
class ComptabiliteServiceValidationTest {

    private static final LocalDate DATE = LocalDate.of(2025, 12, 15);

    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final PeriodeComptableService periode = mock(PeriodeComptableService.class);
    private final ReferenceGenerator references = mock(ReferenceGenerator.class);
    private final JournalEvenementsComptables evenements = mock(JournalEvenementsComptables.class);
    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ComptabiliteService service = new ComptabiliteService(pieces, mock(EcritureGrandLivreRepository.class),
        comptes, mock(MouvementStockRepository.class), mock(VenteRepository.class),
        references, utilisateur, periode,
        new ConversionDeviseService(mock(com.mbsc.finapp.repository.TauxChangeRepository.class)),
        mock(ReleveBancaireRepository.class), evenements, notifications);

    private final User auteur = User.builder().id(1L).email("compta@x").prenom("Claire").nom("COMPTA")
        .roles(new HashSet<>(Set.of(Role.builder().nom(RoleType.COMPTABLE).build()))).build();
    private final User dfin = User.builder().id(2L).email("dfin@x").build();
    private PieceComptable piece;

    @BeforeEach
    void preparer() {
        piece = PieceComptable.builder().id(9L).reference("BR-000042").datePiece(DATE)
            .journal(JournalComptable.OPERATIONS_DIVERSES).libelle("Loyer").statut(StatutPiece.BROUILLON)
            .totalDebit(new BigDecimal("900")).totalCredit(new BigDecimal("900")).createdBy(auteur).build();
        piece.addLigne(ligne("6222", "900", "0"));
        piece.addLigne(ligne("4011", "0", "900"));
        when(pieces.findWithLignesById(9L)).thenReturn(Optional.of(piece));
        when(references.pourPieceDefinitive(DATE)).thenReturn("PC-2025-000007");
    }

    private static EcritureGrandLivre ligne(String compte, String debit, String credit) {
        return EcritureGrandLivre.builder().compte(CompteOHADA.builder().numero(compte).libelle(compte).build())
            .debit(new BigDecimal(debit)).credit(new BigDecimal(credit)).build();
    }

    @Test
    void une_autre_personne_comptabilise_et_la_piece_recoit_son_numero_definitif() {
        when(utilisateur.requireUser()).thenReturn(dfin);

        service.comptabiliser(9L);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
        assertThat(piece.getReference()).isEqualTo("PC-2025-000007");
        assertThat(piece.getComptabiliseePar()).isEqualTo(dfin);
        assertThat(piece.getComptabiliseeLe()).isNotNull();
        verify(evenements).piece(eq("COMPTABILISATION"), eq(piece), any(), any(), eq(dfin));
        // Le comptable qui a saisi la piece (et lui seul) apprend sa comptabilisation, avec son nouveau numero.
        verify(notifications).notifierPiece(eq(List.of()), eq(auteur), eq(dfin), eq(TypeNotification.PIECE_COMPTABILISEE),
            eq("Pièce comptabilisée"), contains("a comptabilisé la pièce PC-2025-000007 du 15/12/2025 : Loyer (900,00 USD). Numéro du brouillon : BR-000042."),
            eq(9L));
    }

    @Test
    void l_auteur_ne_comptabilise_pas_sa_propre_piece() {
        when(utilisateur.requireUser()).thenReturn(auteur);

        assertThatThrownBy(() -> service.comptabiliser(9L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("autre personne");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.BROUILLON);
        verify(references, never()).pourPieceDefinitive(any());
        verify(notifications, never()).notifierPiece(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void la_derogation_du_da_permet_a_l_auteur_de_comptabiliser() {
        when(utilisateur.requireUser()).thenReturn(auteur);
        when(periode.autoValidationAutorisee()).thenReturn(true);

        service.comptabiliser(9L);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.COMPTABILISEE);
        assertThat(piece.getComptabiliseePar()).isEqualTo(auteur);
    }

    @Test
    void un_brouillon_date_d_une_periode_cloturee_ne_se_comptabilise_plus() {
        when(utilisateur.requireUser()).thenReturn(dfin);
        doThrow(new TransitionInvalideException("Période clôturée")).when(periode).verifierDateOuverte(DATE);

        assertThatThrownBy(() -> service.comptabiliser(9L)).hasMessageContaining("Période clôturée");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.BROUILLON);
        assertThat(piece.getReference()).isEqualTo("BR-000042");
    }

    @Test
    void reclasser_une_piece_comptabilisee_exige_un_motif_et_une_periode_ouverte() {
        piece.setStatut(StatutPiece.COMPTABILISEE);
        when(utilisateur.requireUser()).thenReturn(dfin);

        assertThatThrownBy(() -> service.basculerSoldeOuverture(9L, true, " ")).hasMessageContaining("motif");

        service.basculerSoldeOuverture(9L, true, "Reprise des à-nouveaux oubliée");
        assertThat(piece.isSoldeOuverture()).isTrue();
        verify(periode, org.mockito.Mockito.atLeastOnce()).verifierDateOuverte(DATE);
        verify(evenements).piece(eq("BASCULE_SOLDE_OUVERTURE"), eq(piece), any(), eq("Reprise des à-nouveaux oubliée"),
            eq(dfin));
    }

    private void connecte(String role) {
        UserPrincipal principal = mock(UserPrincipal.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_" + role))).when(principal).getAuthorities();
        when(utilisateur.requirePrincipal()).thenReturn(principal);
    }

    /** Une pièce annulée depuis la comptabilité, dont l'extourne est encore en vigueur : restaurable. */
    private void pieceAnnulee() {
        piece.setStatut(StatutPiece.ANNULEE);
        piece.setReference("PC-2025-000007");
        piece.getLignes().forEach(l -> {
            l.getCompte().setActif(true);
            l.getCompte().setImputable(true);
        });
        when(pieces.existsExtourneEnVigueur(9L)).thenReturn(true);
        when(pieces.save(any(PieceComptable.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** Pièce annulée par erreur : le comptable obtient un brouillon identique, relié à elle, que le DFIN validera. */
    @Test
    void une_piece_annulee_se_restaure_en_un_nouveau_brouillon_pour_le_comptable() {
        pieceAnnulee();
        connecte("COMPTABLE");
        when(utilisateur.requireUser()).thenReturn(auteur);
        when(references.pourBrouillon()).thenReturn("BR-000050");

        var r = service.restaurer(9L);

        assertThat(r.reference()).isEqualTo("BR-000050");
        assertThat(r.statut()).isEqualTo(StatutPiece.BROUILLON);
        assertThat(r.pieceRestaureeReference()).isEqualTo("PC-2025-000007");
        assertThat(r.libelle()).startsWith("Restauration de PC-2025-000007");
        assertThat(r.lignes()).hasSize(2);
        assertThat(r.comptabiliseeParEmail()).isNull();
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.ANNULEE);
        verify(references, never()).pourPieceDefinitive(any());
        verify(evenements).piece(eq("RESTAURATION"), any(), isNull(), eq("Restauration de PC-2025-000007"), eq(auteur));
        verify(notifications).notifierPiece(eq(List.of(RoleType.DFIN)), isNull(), eq(auteur),
            eq(TypeNotification.PIECE_A_COMPTABILISER), eq("Restauration à comptabiliser"),
            contains("Claire COMPTA a restauré la pièce annulée PC-2025-000007 du 15/12/2025 : Loyer (900,00 USD) en un nouveau brouillon BR-000050"),
            any());
    }

    /**
     * Le DFIN rétablit la comptabilisation annulée par le comptable (demande du 13/11/2026) : une nouvelle pièce
     * identique, comptabilisée tout de suite et reliée à la pièce annulée, qui reste ANNULEE avec son extourne.
     */
    @Test
    void le_dfin_retablit_la_comptabilisation_d_une_piece_annulee() {
        pieceAnnulee();
        piece.getLignes().get(0).setTauxApplique(new BigDecimal("2200"));
        connecte("DFIN");
        when(utilisateur.requireUser()).thenReturn(dfin);
        when(references.pourPieceDefinitive(DATE)).thenReturn("PC-2025-000011");
        when(pieces.findFirstByPieceOrigine_IdAndStatutOrderByIdDesc(9L, StatutPiece.COMPTABILISEE))
            .thenReturn(Optional.of(PieceComptable.builder().id(10L).reference("PC-2025-000008").createdBy(auteur).build()));

        var r = service.restaurer(9L);

        assertThat(r.reference()).isEqualTo("PC-2025-000011");
        assertThat(r.statut()).isEqualTo(StatutPiece.COMPTABILISEE);
        assertThat(r.pieceRestaureeReference()).isEqualTo("PC-2025-000007");
        assertThat(r.comptabiliseeParEmail()).isEqualTo("dfin@x");
        assertThat(r.comptabiliseeLe()).isNotNull();
        assertThat(r.totalDebit()).isEqualByComparingTo("900");
        assertThat(r.totalCredit()).isEqualByComparingTo("900");
        assertThat(piece.getStatut()).isEqualTo(StatutPiece.ANNULEE);
        verify(references, never()).pourBrouillon();
        ArgumentCaptor<PieceComptable> enregistree = ArgumentCaptor.forClass(PieceComptable.class);
        verify(pieces).save(enregistree.capture());
        assertThat(enregistree.getValue().getPieceRestauree()).isSameAs(piece);
        assertThat(enregistree.getValue().getComptabiliseePar()).isEqualTo(dfin);
        // Chaque ligne reprend compte, montants et taux de la pièce d'origine.
        var lignes = enregistree.getValue().getLignes();
        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).getCompte().getNumero()).isEqualTo("6222");
        assertThat(lignes.get(0).getDebit()).isEqualByComparingTo("900");
        assertThat(lignes.get(1).getCompte().getNumero()).isEqualTo("4011");
        assertThat(lignes.get(1).getCredit()).isEqualByComparingTo("900");
        assertThat(enregistree.getValue().getLignes().get(0).getTauxApplique()).isEqualByComparingTo("2200");
        verify(evenements).piece(eq("RESTAURATION"), any(), isNull(), eq("Comptabilisation rétablie de PC-2025-000007"),
            eq(dfin));
        // Le comptable qui avait annule la piece (auteur de son extourne) apprend que l'annulation est levee.
        verify(notifications).notifierPiece(eq(List.of()), eq(auteur), eq(dfin), eq(TypeNotification.PIECE_RESTAUREE),
            eq("Comptabilisation rétablie"), contains("la nouvelle pièce PC-2025-000011 la reprend"), any());
    }

    @Test
    void l_administrateur_retablit_aussi_la_comptabilisation() {
        pieceAnnulee();
        User admin = User.builder().id(3L).email("admin@x").build();
        connecte("ADMIN");
        when(utilisateur.requireUser()).thenReturn(admin);
        when(references.pourPieceDefinitive(DATE)).thenReturn("PC-2025-000012");

        var r = service.restaurer(9L);

        assertThat(r.statut()).isEqualTo(StatutPiece.COMPTABILISEE);
        assertThat(r.reference()).isEqualTo("PC-2025-000012");
        assertThat(r.comptabiliseeParEmail()).isEqualTo("admin@x");
        // L'administrateur leve une annulation : le DFIN est prevenu, et faute de connaitre qui avait annule, tous les
        // comptables.
        verify(notifications).notifierPiece(eq(List.of(RoleType.DFIN, RoleType.COMPTABLE)), isNull(), eq(admin),
            eq(TypeNotification.PIECE_RESTAUREE), eq("Comptabilisation rétablie"), any(), any());
    }

    /** Saisie d'une piece par le comptable : le DFIN est prevenu qu'elle attend sa validation. */
    @Test
    void la_saisie_d_une_piece_previent_le_dfin() {
        when(utilisateur.requireUser()).thenReturn(auteur);
        when(references.pourBrouillon()).thenReturn("BR-000060");
        when(pieces.save(any(PieceComptable.class))).thenAnswer(i -> {
            PieceComptable p = i.getArgument(0);
            p.setId(60L);
            return p;
        });
        for (String numero : List.of("6222", "4011")) {
            when(comptes.findByNumero(numero)).thenReturn(Optional.of(
                CompteOHADA.builder().numero(numero).libelle(numero).actif(true).imputable(true).build()));
        }

        service.creerPiece(new PieceCreateRequest(DATE, JournalComptable.OPERATIONS_DIVERSES, "Loyer de décembre", null, false,
            List.of(new LigneEcritureRequest("6222", new BigDecimal("1440"), BigDecimal.ZERO, null),
                new LigneEcritureRequest("4011", BigDecimal.ZERO, new BigDecimal("1440"), null))));

        verify(notifications).notifierPiece(eq(List.of(RoleType.DFIN)), isNull(), eq(auteur),
            eq(TypeNotification.PIECE_A_COMPTABILISER), eq("Pièce à comptabiliser"),
            contains("Claire COMPTA a saisi la pièce BR-000060 du 15/12/2025 : Loyer de décembre (1"), eq(60L));
    }

    /**
     * Règle du 13/11/2026 : une pièce saisie au 1er janvier qui ne porte que des comptes de bilan est un solde
     * d'ouverture, case cochée ou non, et son libellé le dit ; une charge du 1er janvier reste un mouvement.
     */
    @Test
    void une_piece_du_1er_janvier_aux_comptes_de_bilan_est_classee_en_solde_d_ouverture() {
        LocalDate premierJanvier = LocalDate.of(2026, 1, 1);
        when(utilisateur.requireUser()).thenReturn(auteur);
        when(references.pourBrouillon()).thenReturn("BR-000061", "BR-000062");
        when(pieces.save(any(PieceComptable.class))).thenAnswer(i -> i.getArgument(0));
        for (String numero : List.of("2441", "4011", "6222")) {
            when(comptes.findByNumero(numero)).thenReturn(Optional.of(
                CompteOHADA.builder().numero(numero).libelle(numero).actif(true).imputable(true).build()));
        }

        var ouverture = service.creerPiece(new PieceCreateRequest(premierJanvier, JournalComptable.OPERATIONS_DIVERSES,
            "Matériel de bureau", null, false,
            List.of(new LigneEcritureRequest("2441", new BigDecimal("26480"), BigDecimal.ZERO, null),
                new LigneEcritureRequest("4011", BigDecimal.ZERO, new BigDecimal("26480"), null))));
        var charge = service.creerPiece(new PieceCreateRequest(premierJanvier, JournalComptable.OPERATIONS_DIVERSES,
            "Loyer du 1er janvier", null, false,
            List.of(new LigneEcritureRequest("6222", new BigDecimal("500"), BigDecimal.ZERO, null),
                new LigneEcritureRequest("4011", BigDecimal.ZERO, new BigDecimal("500"), null))));

        assertThat(ouverture.soldeOuverture()).isTrue();
        assertThat(ouverture.libelle()).isEqualTo("Matériel de bureau - Solde d'ouverture");
        assertThat(ouverture.lignes()).extracting(l -> l.libelle()).containsOnly("Matériel de bureau - Solde d'ouverture");
        assertThat(charge.soldeOuverture()).isFalse();
        assertThat(charge.libelle()).isEqualTo("Loyer du 1er janvier");
        assertThat(charge.lignes()).extracting(l -> l.libelle()).containsOnly("Loyer du 1er janvier");
    }

    @Test
    void le_resume_d_une_piece_donne_sa_date_son_libelle_et_son_montant() {
        assertThat(ComptabiliteService.resume(piece)).isEqualTo("du 15/12/2025 : Loyer (900,00 USD)");
        assertThat(ComptabiliteService.nom(auteur)).isEqualTo("Claire COMPTA");
        assertThat(ComptabiliteService.nom(dfin)).isEqualTo("dfin@x");
    }

    /** Une comptabilisation rétablie ne s'écrit pas dans une période close : la règle de l'extourne s'applique. */
    @Test
    void une_comptabilisation_ne_se_retablit_pas_dans_une_periode_cloturee() {
        pieceAnnulee();
        connecte("DFIN");
        when(utilisateur.requireUser()).thenReturn(dfin);
        doThrow(new TransitionInvalideException("Période clôturée")).when(periode).verifierDateOuverte(DATE);

        assertThatThrownBy(() -> service.restaurer(9L)).hasMessageContaining("Période clôturée");
        verify(pieces, never()).save(any(PieceComptable.class));
    }

    @Test
    void une_piece_deja_restauree_ou_encore_comptabilisee_ne_se_restaure_pas() {
        connecte("DFIN");
        when(utilisateur.requireUser()).thenReturn(dfin);
        piece.setStatut(StatutPiece.COMPTABILISEE);
        assertThatThrownBy(() -> service.restaurer(9L)).hasMessageContaining("ne peut pas être restaurée");
        piece.setStatut(StatutPiece.ANNULEE);
        when(pieces.existsExtourneEnVigueur(9L)).thenReturn(true);
        when(pieces.existsRestaurationActive(9L)).thenReturn(true);
        assertThatThrownBy(() -> service.restaurer(9L)).hasMessageContaining("ne peut pas être restaurée");
        verify(pieces, never()).save(any(PieceComptable.class));
    }

    /**
     * Si l'extourne a elle-même été annulée (extourne d'extourne), la comptabilisation de la pièce est déjà
     * rétablie : la restaurer l'écrirait une seconde fois.
     */
    @Test
    void une_annulation_deja_neutralisee_ne_se_restaure_pas() {
        pieceAnnulee();
        when(pieces.existsExtourneEnVigueur(9L)).thenReturn(false);
        connecte("DFIN");
        when(utilisateur.requireUser()).thenReturn(dfin);

        assertThatThrownBy(() -> service.restaurer(9L)).hasMessageContaining("annulation")
            .hasMessageContaining("ne peut pas être restaurée");
        verify(pieces, never()).save(any(PieceComptable.class));
    }

    @Test
    void une_reevaluation_de_devises_ne_se_restaure_pas() {
        pieceAnnulee();
        piece.setReevaluationDevise(true);
        connecte("DFIN");
        when(utilisateur.requireUser()).thenReturn(dfin);

        assertThatThrownBy(() -> service.restaurer(9L)).hasMessageContaining("ne peut pas être restaurée");
        verify(pieces, never()).save(any(PieceComptable.class));
    }
}

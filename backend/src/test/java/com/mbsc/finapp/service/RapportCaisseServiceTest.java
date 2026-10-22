package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TauxChange;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PeriodeRapport;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rapport de caisse : la lecture du grand livre (solde d'ouverture, mouvements de la periode), la contrepartie tiree des
 * autres lignes de la piece, la devise d'affichage et ses taux.
 */
class RapportCaisseServiceTest {

    private static final LocalDate JOUR = LocalDate.parse("2026-10-21");   // un mercredi

    private final EcritureGrandLivreRepository repository = mock(EcritureGrandLivreRepository.class);
    private final TauxChangeRepository taux = mock(TauxChangeRepository.class);
    private final RapportCaisseService service = new RapportCaisseService(repository, new ConversionDeviseService(taux));

    private long sequence = 100;

    private static CompteOHADA compte(String numero, String libelle) {
        return CompteOHADA.builder().numero(numero).libelle(libelle).build();
    }

    private EcritureGrandLivre ligne(CompteOHADA compte, String debit, String credit, PieceComptable piece, TransactionCaisse operation) {
        return EcritureGrandLivre.builder().id(++sequence).compte(compte).debit(new BigDecimal(debit)).credit(new BigDecimal(credit))
            .libelle("Ligne " + compte.getNumero()).dateEcriture(JOUR).piece(piece).transaction(operation).build();
    }

    private static PieceComptable piece(long id, String reference, StatutPiece statut) {
        return PieceComptable.builder().id(id).reference(reference).datePiece(JOUR).libelle("Piece " + reference).statut(statut).build();
    }

    private final CompteOHADA caisse = compte("5711", "Caisse siège social");

    private void ouverture(String debit, String credit) {
        when(repository.soldeAnterieur(eq("571"), any())).thenReturn(new Object[] {new BigDecimal(debit), new BigDecimal(credit)});
    }

    @Test
    void le_rapport_du_jour_donne_chaque_entree_et_chaque_sortie_avec_sa_contrepartie() {
        ouverture("500.00", "200.00");   // solde d'ouverture : 300,00
        PieceComptable p1 = piece(1, "PC-1", StatutPiece.COMPTABILISEE);
        PieceComptable p2 = piece(2, "PC-2", StatutPiece.ANNULEE);
        PieceComptable p4 = piece(4, "PC-4", StatutPiece.COMPTABILISEE);
        PieceComptable p5 = piece(5, "PC-5", StatutPiece.COMPTABILISEE);
        PieceComptable p6 = piece(6, "PC-6", StatutPiece.COMPTABILISEE);
        User cyril = User.builder().id(1L).prenom("Cyril").nom("Mwamba").email("caisse@test").build();
        Instant instant = Instant.parse("2026-10-21T09:15:00Z");
        TransactionCaisse t1 = TransactionCaisse.builder().id(11L).reference("TRX-1").numeroRecu("REC-1").caissier(cyril).dateOperation(instant).build();
        TransactionCaisse t3 = TransactionCaisse.builder().id(13L).reference("TRX-3").numeroRecu("REC-3").caissier(cyril).build();

        // 1. vente en caisse 116 : D 5711 / C 7011 100 / C 4457 16
        EcritureGrandLivre c1 = ligne(caisse, "116.00", "0", p1, t1);
        EcritureGrandLivre v1 = ligne(compte("7011", "Ventes de marchandises"), "0", "100.00", p1, t1);
        EcritureGrandLivre tva1 = ligne(compte("4457", "TVA collectée"), "0", "16.00", p1, t1);
        // 2. dépense 50 d'une pièce annulée : D 6047 40 / D 4452 10 / C 5711
        EcritureGrandLivre c2 = ligne(caisse, "0", "50.00", p2, null);
        EcritureGrandLivre d2 = ligne(compte("6047", "Fournitures de bureau"), "40.00", "0", p2, null);
        EcritureGrandLivre tva2 = ligne(compte("4452", "TVA récupérable"), "10.00", "0", p2, null);
        // 3. écriture ancienne sans pièce, reliée à son opération : C 5711 30 / D 6588
        EcritureGrandLivre c3 = ligne(caisse, "0", "30.00", null, t3);
        EcritureGrandLivre d3 = ligne(compte("6588", "Charges diverses"), "30.00", "0", null, t3);
        // 4. ligne de caisse seule dans sa pièce : aucune contrepartie
        EcritureGrandLivre c4 = ligne(caisse, "7.00", "0", p4, null);
        // 5. égalité de montants : la contrepartie principale est le plus petit numéro
        EcritureGrandLivre c5 = ligne(caisse, "30.00", "0", p5, null);
        EcritureGrandLivre a5 = ligne(compte("7021", "Ventes de produits"), "0", "15.00", p5, null);
        EcritureGrandLivre b5 = ligne(compte("7011", "Ventes de marchandises"), "0", "15.00", p5, null);
        // 6. plus de trois comptes secondaires : les trois premiers, puis « ... »
        EcritureGrandLivre c6 = ligne(caisse, "100.00", "0", p6, null);
        List<EcritureGrandLivre> pieces6 = List.of(c6,
            ligne(compte("7011", "Ventes de marchandises"), "0", "60.00", p6, null), ligne(compte("4457", "TVA collectée"), "0", "12.00", p6, null),
            ligne(compte("7021", "Ventes de produits"), "0", "10.00", p6, null), ligne(compte("7031", "Ventes de services"), "0", "9.00", p6, null),
            ligne(compte("7041", "Travaux"), "0", "9.00", p6, null));

        when(repository.mouvementsDeCaisse("571", JOUR, JOUR)).thenReturn(List.of(c1, c2, c3, c4, c5, c6));
        List<EcritureGrandLivre> lignesPieces = new java.util.ArrayList<>(List.of(c1, v1, tva1, c2, d2, tva2, c4, c5, a5, b5));
        lignesPieces.addAll(pieces6);
        when(repository.lignesDesPieces(anyCollection())).thenReturn(lignesPieces);
        when(repository.lignesDesTransactions(anyCollection())).thenReturn(List.of(c3, d3));

        RapportCaisseResponse r = service.rapport(PeriodeRapport.JOUR, JOUR, null);

        assertThat(r.devise()).isEqualTo(Devise.USD);
        assertThat(r.du()).isEqualTo(JOUR);
        assertThat(r.au()).isEqualTo(JOUR);
        assertThat(r.soldeOuverture()).isEqualByComparingTo("300.00");
        assertThat(r.entrees()).extracting(RapportCaisseResponse.Mouvement::pieceReference).containsExactly("PC-1", "PC-4", "PC-5", "PC-6");
        assertThat(r.sorties()).extracting(RapportCaisseResponse.Mouvement::pieceReference).containsExactly("PC-2", "TRX-3");
        assertThat(r.totalEntrees()).isEqualByComparingTo("253.00");   // 116 + 7 + 30 + 100
        assertThat(r.totalSorties()).isEqualByComparingTo("80.00");    // 50 + 30
        assertThat(r.soldeCloture()).isEqualByComparingTo("473.00");   // 300 + 253 - 80

        RapportCaisseResponse.Mouvement vente = r.entrees().get(0);
        assertThat(vente.compteNumero()).isEqualTo("7011");
        assertThat(vente.compteLibelle()).isEqualTo("Ventes de marchandises");
        assertThat(vente.autresComptes()).isEqualTo("4457");
        assertThat(vente.numeroRecu()).isEqualTo("REC-1");
        assertThat(vente.caissierNom()).isEqualTo("Cyril Mwamba");
        assertThat(vente.heure()).isEqualTo(Instant.parse("2026-10-21T09:15:00Z"));
        assertThat(vente.annulee()).isFalse();
        assertThat(vente.libelle()).isEqualTo("Ligne 5711");

        RapportCaisseResponse.Mouvement depense = r.sorties().get(0);
        assertThat(depense.compteNumero()).isEqualTo("6047");
        assertThat(depense.autresComptes()).isEqualTo("4452");
        assertThat(depense.annulee()).isTrue();
        assertThat(depense.numeroRecu()).isNull();   // pas d'operation de caisse : ni recu ni caissier ni heure
        assertThat(depense.heure()).isNull();

        RapportCaisseResponse.Mouvement ancienne = r.sorties().get(1);
        assertThat(ancienne.compteNumero()).isEqualTo("6588");
        assertThat(ancienne.pieceReference()).isEqualTo("TRX-3");   // sans piece : la reference de l'operation
        assertThat(ancienne.numeroRecu()).isEqualTo("REC-3");

        assertThat(r.entrees().get(1).compteNumero()).isNull();   // ligne de caisse seule
        assertThat(r.entrees().get(1).autresComptes()).isNull();
        assertThat(r.entrees().get(2).compteNumero()).isEqualTo("7011");   // egalite : le plus petit numero
        assertThat(r.entrees().get(2).autresComptes()).isEqualTo("7021");
        assertThat(r.entrees().get(3).compteNumero()).isEqualTo("7011");
        assertThat(r.entrees().get(3).autresComptes()).isEqualTo("4457, 7021, 7031, ...");

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(repository).lignesDesPieces(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L, 4L, 5L, 6L);
        ArgumentCaptor<Collection<Long>> transactions = ArgumentCaptor.forClass(Collection.class);
        verify(repository).lignesDesTransactions(transactions.capture());
        assertThat(transactions.getValue()).containsExactly(13L);
    }

    @Test
    void la_semaine_et_le_mois_lisent_leurs_bornes_et_le_solde_d_ouverture_du_premier_jour() {
        ouverture("0", "0");
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of());

        RapportCaisseResponse semaine = service.rapport(PeriodeRapport.SEMAINE, JOUR, null);
        verify(repository).mouvementsDeCaisse("571", LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-25"));
        verify(repository).soldeAnterieur("571", LocalDate.parse("2026-10-19"));
        assertThat(semaine.du()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(semaine.au()).isEqualTo(LocalDate.parse("2026-10-25"));

        RapportCaisseResponse mois = service.rapport(PeriodeRapport.MOIS, JOUR, null);
        verify(repository).mouvementsDeCaisse("571", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        verify(repository).soldeAnterieur("571", LocalDate.parse("2026-10-01"));
        assertThat(mois.periode()).isEqualTo(PeriodeRapport.MOIS);
        assertThat(mois.date()).isEqualTo(JOUR);
        verify(repository, never()).lignesDesPieces(anyCollection());   // aucun mouvement : pas de requete de contreparties
        verify(repository, never()).lignesDesTransactions(anyCollection());
    }

    @Test
    void sans_periode_ni_date_c_est_le_jour_d_aujourd_hui() {
        ouverture("0", "0");
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of());

        RapportCaisseResponse r = service.rapport(null, null, null);

        assertThat(r.periode()).isEqualTo(PeriodeRapport.JOUR);
        assertThat(r.date()).isEqualTo(LocalDate.now());
        assertThat(r.du()).isEqualTo(LocalDate.now());
    }

    @Test
    void le_solde_d_ouverture_se_lit_aussi_quand_spring_imbrique_le_tableau() {
        when(repository.soldeAnterieur(eq("571"), any())).thenReturn(new Object[] {new Object[] {new BigDecimal("80.00"), new BigDecimal("30.00")}});
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of());

        assertThat(service.rapport(PeriodeRapport.JOUR, JOUR, null).soldeOuverture()).isEqualByComparingTo("50.00");
    }

    @Test
    void en_francs_les_montants_sont_convertis_au_taux_du_dernier_jour_de_la_periode() {
        ouverture("1.00", "0");
        LocalDate milieuDuMoisPasse = LocalDate.now().minusMonths(2).withDayOfMonth(15);
        LocalDate finDuMoisPasse = milieuDuMoisPasse.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        when(taux.findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(finDuMoisPasse))
            .thenReturn(Optional.of(TauxChange.builder().taux(new BigDecimal("2700")).dateEffet(finDuMoisPasse.withDayOfMonth(1)).build()));
        PieceComptable p = piece(1, "PC-1", StatutPiece.COMPTABILISEE);
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of(ligne(caisse, "2.00", "0", p, null)));
        when(repository.lignesDesPieces(anyCollection())).thenReturn(List.of());

        RapportCaisseResponse r = service.rapport(PeriodeRapport.MOIS, milieuDuMoisPasse, Devise.CDF);

        assertThat(r.devise()).isEqualTo(Devise.CDF);
        assertThat(r.taux()).isEqualByComparingTo("2700");
        assertThat(r.dateTaux()).isEqualTo(finDuMoisPasse);   // un mois passe : le taux de sa fin, pas celui d'aujourd'hui
        assertThat(r.soldeOuverture()).isEqualByComparingTo("2700");
        assertThat(r.totalEntrees()).isEqualByComparingTo("5400");
        assertThat(r.soldeCloture()).isEqualByComparingTo("8100");
    }

    @Test
    void une_periode_future_est_convertie_au_taux_d_aujourd_hui() {
        ouverture("0", "0");
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of());
        when(taux.findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(LocalDate.now()))
            .thenReturn(Optional.of(TauxChange.builder().taux(new BigDecimal("2800")).dateEffet(LocalDate.now()).build()));

        RapportCaisseResponse r = service.rapport(PeriodeRapport.MOIS, LocalDate.now().plusYears(1), Devise.CDF);

        assertThat(r.dateTaux()).isEqualTo(LocalDate.now());
        assertThat(r.taux()).isEqualByComparingTo("2800");
    }

    @Test
    void sans_taux_defini_l_affichage_en_francs_est_refuse_avec_un_message_clair() {
        when(taux.findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(taux.findAllByOrderByDateEffetDescCreatedAtDesc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.rapport(PeriodeRapport.JOUR, JOUR, Devise.CDF))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Aucun taux de change")
            .hasMessageContaining("afficher en FC")
            .hasMessageContaining("Demandez à un administrateur");
        verify(repository, never()).mouvementsDeCaisse(any(), any(), any());
    }

    @Test
    void en_dollars_aucun_taux_n_est_consulte() {
        ouverture("0", "0");
        when(repository.mouvementsDeCaisse(any(), any(), any())).thenReturn(List.of());

        service.rapport(PeriodeRapport.JOUR, JOUR, Devise.USD);
        service.rapport(PeriodeRapport.JOUR, JOUR, null);

        verifyNoInteractions(taux);
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.admin.ImportJournalResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Import en mode « Remplacer » : les pieces rattachees a une operation
 * (vente, mouvement de stock, paie...) ne sont jamais effacees.
 *
 * <p>Avant, le remplacement effacait les ecritures d'une piece de stock puis
 * echouait sur la cle etrangere {@code mouvements_stock_piece_id_fkey} : erreur
 * interne a l'ecran au lieu d'un message utile.</p>
 */
class ImportJournalRemplacementTest {

    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
    private final PieceComptableRepository pieces = mock(PieceComptableRepository.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final EtablissementTresorerieService etablissements = mock(EtablissementTresorerieService.class);
    private final PiecesRattachees rattachees = mock(PiecesRattachees.class);

    private final ImportJournalService service = new ImportJournalService(comptes, ecritures, pieces, comptabilite,
        utilisateur, mock(DocumentationCompteService.class), mock(ImportIaService.class),
        mock(ConversionDeviseService.class), etablissements, rattachees);

    private static final String JOURNAL = """
        Date;N° Pièce;Journal;Compte;Intitulé du compte;Libellé écriture;Débit;Crédit
        01/01/2026;PC-1;OPERATIONS_DIVERSES;571;Caisse;Apport;100;0
        01/01/2026;PC-1;OPERATIONS_DIVERSES;4621.1;Associé;Apport;0;100
        """;

    @BeforeEach
    void preparer() {
        when(comptes.findByNumero(anyString())).thenAnswer(i -> Optional.of(CompteOHADA.builder()
            .numero(i.getArgument(0)).libelle("Compte").classe(5).imputable(true).actif(true).build()));
        when(utilisateur.requireUser()).thenReturn(mock(User.class));
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), any(), any()))
            .thenReturn(mock(PieceComptable.class));
        when(etablissements.rattacherComptesOrphelins(anyCollection(), anyBoolean())).thenReturn(List.of());
    }

    private ImportJournalResponse importer() {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "journal.csv", "text/csv",
            JOURNAL.getBytes(StandardCharsets.UTF_8));
        return service.importer(fichier, false, null, null, null, ImportJournalService.ModeImport.REMPLACER);
    }

    @Test
    void conserveLesPiecesRattacheesAUneOperation() {
        when(rattachees.identifiants()).thenReturn(new HashSet<>(Set.of(7L, 12L)));

        ImportJournalResponse rapport = importer();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> protegeesEcritures = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> protegeesPieces = ArgumentCaptor.forClass(Collection.class);
        verify(ecritures).supprimerEcrituresSansTransaction(protegeesEcritures.capture());
        verify(pieces).supprimerPiecesSansEcriture(protegeesPieces.capture());
        assertThat(protegeesEcritures.getValue()).contains(7L, 12L);
        assertThat(protegeesPieces.getValue()).contains(7L, 12L);
        assertThat(rapport.avertissements()).anyMatch(a -> a.startsWith("2 piece(s) rattachee(s)"));
    }

    @Test
    void sansPieceRattacheeLaListeTransmiseNEstJamaisVide() {
        when(rattachees.identifiants()).thenReturn(new HashSet<>());

        ImportJournalResponse rapport = importer();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> protegees = ArgumentCaptor.forClass(Collection.class);
        verify(pieces).supprimerPiecesSansEcriture(protegees.capture());
        assertThat(protegees.getValue()).isNotEmpty();
        assertThat(rapport.avertissements()).noneMatch(a -> a.contains("rattachee"));
    }

    @Test
    void uneReferenceRestanteDonneUnMessageClairEtRienNEstImporte() {
        when(rattachees.identifiants()).thenReturn(new HashSet<>());
        when(pieces.supprimerPiecesSansEcriture(anyCollection()))
            .thenThrow(new DataIntegrityViolationException("mouvements_stock_piece_id_fkey"));

        assertThatThrownBy(this::importer)
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).contains("Aucune ecriture n'a ete modifiee");
            });
        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), any(), any());
    }
}

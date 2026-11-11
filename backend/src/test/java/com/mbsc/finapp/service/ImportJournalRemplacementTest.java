package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.admin.ImportJournalResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Import en mode « Remplacer » : toute la comptabilite et les operations qui en
 * produisent (notes de frais, caisse, ventes, stock, paie...) sont effacees
 * avant l'import, comme sur une base neuve.
 *
 * <p>Avant, les pieces rattachees a une operation etaient conservees : leurs
 * ecritures figuraient en double si le fichier les contenait aussi.</p>
 */
class ImportJournalRemplacementTest {

    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final EtablissementTresorerieService etablissements = mock(EtablissementTresorerieService.class);
    private final EffacementComptable effacement = mock(EffacementComptable.class);
    private final PeriodeComptableService periodes = mock(PeriodeComptableService.class);

    private final ImportJournalService service = new ImportJournalService(comptes, comptabilite,
        utilisateur, mock(DocumentationCompteService.class), mock(ImportIaService.class),
        mock(ConversionDeviseService.class), etablissements, effacement, periodes);

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

    private ImportJournalResponse importer(boolean simulation) {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "journal.csv", "text/csv",
            JOURNAL.getBytes(StandardCharsets.UTF_8));
        return service.importer(fichier, simulation, null, null, null, ImportJournalService.ModeImport.REMPLACER);
    }

    @Test
    void effaceToutAvantDImporter() {
        when(effacement.effacer()).thenReturn(Map.of("notes_frais", 7, "ventes", 8));

        ImportJournalResponse rapport = importer(false);

        var ordre = inOrder(effacement, comptabilite);
        ordre.verify(effacement).effacer();
        ordre.verify(comptabilite).creerPieceInterne(any(), anyString(), any(), any(), any());
        assertThat(rapport.avertissements()).noneMatch(a -> a.contains("double"));
    }

    @Test
    void lAnalyseAnnonceCeQuiSeraEffaceSansRienEffacer() {
        when(effacement.apercu()).thenReturn(List.of("7 note(s) de frais", "8 vente(s)"));

        ImportJournalResponse rapport = importer(true);

        verify(effacement, never()).effacer();
        assertThat(rapport.avertissements())
            .anyMatch(a -> a.contains("effacera") && a.contains("7 note(s) de frais, 8 vente(s)"));
    }

    @Test
    void uneComptabiliteClotureeNeSeRemplacePas() {
        when(periodes.dateCloture()).thenReturn(java.time.LocalDate.of(2025, 12, 31));

        assertThatThrownBy(() -> importer(false))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).contains("cloturee jusqu'au 2025-12-31"));
        verify(effacement, never()).effacer();
        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), any(), any());
    }

    @Test
    void uneReferenceRestanteDonneUnMessageClairEtRienNEstImporte() {
        when(effacement.effacer()).thenThrow(new DataIntegrityViolationException("mouvements_stock_piece_id_fkey"));

        assertThatThrownBy(() -> importer(false))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getReason()).contains("Rien n'a ete modifie");
            });
        verify(comptabilite, never()).creerPieceInterne(any(), anyString(), any(), any(), any());
    }
}

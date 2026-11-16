package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.admin.ImportJournalResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regle du 13/11/2026 : dans un journal importe, toute operation du 1er janvier est un solde d'ouverture. La reprise
 * des a-nouveaux (comptes de bilan seulement) recoit le marqueur a l'import ; une piece du 1er janvier qui porte une
 * charge ou un produit reste un mouvement de l'exercice, et l'analyse du fichier le dit.
 */
class ImportJournalSoldeOuvertureTest {

    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final ComptabiliteService comptabilite = mock(ComptabiliteService.class);
    private final CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
    private final EtablissementTresorerieService etablissements = mock(EtablissementTresorerieService.class);

    private final ImportJournalService service = new ImportJournalService(comptes, comptabilite,
        utilisateur, mock(DocumentationCompteService.class), mock(ImportIaService.class),
        new NumerosComptes(mock(org.springframework.jdbc.core.JdbcTemplate.class)),
        mock(ConversionDeviseService.class), etablissements, mock(EffacementComptable.class),
        mock(PeriodeComptableService.class));

    /** Pieces creees par l'import, dans l'ordre du fichier. */
    private final List<PieceComptable> creees = new ArrayList<>();

    private static final String JOURNAL = """
        Date;N° Pièce;Journal;Compte;Intitulé du compte;Libellé écriture;Débit;Crédit
        01/01/2026;PC-1;OPERATIONS_DIVERSES;2441;Matériel de bureau;Reprise;26480;0
        01/01/2026;PC-1;OPERATIONS_DIVERSES;571;Caisse siège social;Reprise;3595;0
        01/01/2026;PC-1;OPERATIONS_DIVERSES;46211;Associés;Reprise;0;30075
        01/01/2026;PC-2;OPERATIONS_DIVERSES;6222;Loyer;Loyer du 1er janvier;500;0
        01/01/2026;PC-2;OPERATIONS_DIVERSES;571;Caisse siège social;Loyer du 1er janvier;0;500
        15/01/2026;PC-3;OPERATIONS_DIVERSES;2441;Matériel de bureau;Achat;85;0
        15/01/2026;PC-3;OPERATIONS_DIVERSES;571;Caisse siège social;Achat;0;85
        """;

    @BeforeEach
    void preparer() {
        when(comptes.findByNumero(anyString())).thenAnswer(i -> Optional.of(CompteOHADA.builder()
            .numero(i.getArgument(0)).libelle("Compte").imputable(true).actif(true).build()));
        when(utilisateur.requireUser()).thenReturn(mock(User.class));
        when(comptabilite.creerPieceInterne(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            PieceComptable piece = PieceComptable.builder().reference("PC-2026-00000" + (creees.size() + 1)).build();
            creees.add(piece);
            return piece;
        });
        when(etablissements.rattacherComptesOrphelins(anyCollection(), anyBoolean())).thenReturn(List.of());
    }

    private ImportJournalResponse importer(boolean simulation) {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "journal.csv", "text/csv",
            JOURNAL.getBytes(StandardCharsets.UTF_8));
        return service.importer(fichier, simulation, null, null, null, ImportJournalService.ModeImport.AJOUTER);
    }

    @Test
    void la_reprise_des_a_nouveaux_du_1er_janvier_est_importee_en_solde_d_ouverture() {
        importer(false);

        assertThat(creees).hasSize(3);
        assertThat(creees.get(0).isSoldeOuverture()).as("PC-1 : 1er janvier, comptes de bilan").isTrue();
        assertThat(creees.get(1).isSoldeOuverture()).as("PC-2 : 1er janvier, mais une charge").isFalse();
        assertThat(creees.get(2).isSoldeOuverture()).as("PC-3 : 15 janvier").isFalse();
    }

    @Test
    void l_analyse_annonce_les_pieces_du_1er_janvier_sans_rien_importer() {
        ImportJournalResponse rapport = importer(true);

        assertThat(creees).isEmpty();
        assertThat(rapport.avertissements())
            .anyMatch(a -> a.contains("Pièce PC-1 du 1er janvier") && a.contains("solde d'ouverture"))
            .anyMatch(a -> a.contains("Pièce PC-2 du 1er janvier") && a.contains("comptes de gestion (6222)")
                && a.contains("reste dans les mouvements"))
            .noneMatch(a -> a.contains("PC-3"));
    }
}

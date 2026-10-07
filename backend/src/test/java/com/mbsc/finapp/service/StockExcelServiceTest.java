package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;
import com.mbsc.finapp.dto.logistique.StockNiveauResponse;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classeurs Excel du stock : mêmes lignes et mêmes filtres que l'écran, nom de
 * l'article (et non son seul code), totaux en formules.
 */
class StockExcelServiceTest {

    private final StockService stockService = mock(StockService.class);
    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final EntrepotRepository entrepotRepository = mock(EntrepotRepository.class);
    private final ParametresEntrepriseRepository parametresRepository = mock(ParametresEntrepriseRepository.class);
    private final StockExcelService service = new StockExcelService(
        stockService, articleRepository, entrepotRepository, parametresRepository);

    private static BigDecimal d(String valeur) {
        return new BigDecimal(valeur);
    }

    private static XSSFWorkbook ouvrir(byte[] octets) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(octets));
    }

    private static String texte(Sheet sh, int ligne, int colonne) {
        Cell c = sh.getRow(ligne).getCell(colonne);
        return c == null ? null : c.getStringCellValue();
    }

    private void societe(String nom) {
        when(parametresRepository.findAll()).thenReturn(List.of(ParametresEntreprise.builder().nom(nom).build()));
    }

    private static StockGrandLivreResponse mouvement(long id, LocalDate date, String code, String libelle, String entrepot,
                                                      String reference, String entree, String sortie, String solde,
                                                      String cmp, String valeur) {
        return new StockGrandLivreResponse(id, date, code, libelle, entrepot, reference,
            d(entree), d(sortie), d(solde), d(cmp), d(valeur), id, StatutMouvement.VALIDE, false);
    }

    private static StockNiveauResponse niveau(String code, String libelle, String unite, String entrepot,
                                              String quantite, String cmp, String valeur, String stockMin, boolean sousSeuil) {
        return new StockNiveauResponse(1L, code, libelle, unite, 1L, entrepot,
            d(quantite), d(valeur), d(cmp), d(valeur), d(cmp), d(stockMin), sousSeuil);
    }

    // ---------------------------------------------------------------------
    // Grand livre de stock
    // ---------------------------------------------------------------------

    @Test
    void le_grand_livre_affiche_le_nom_de_l_article_puis_son_code() throws IOException {
        societe("MBSC SARL");
        LocalDate du = LocalDate.of(2026, 1, 1);
        LocalDate au = LocalDate.of(2026, 10, 7);
        when(stockService.grandLivreStock(null, null, du, au)).thenReturn(List.of(
            mouvement(1, LocalDate.of(2026, 10, 6), "BRA-01", "Primus 55CL", "ENT-01", "MS-2026-000001",
                "32", "0", "32", "1.1666666", "37.32"),
            mouvement(2, LocalDate.of(2026, 10, 6), "BRSIM-01", "Simba 55CL", "ENT-01", "MS-2026-000002",
                "0", "2", "39", "1.17", "45.48")));

        try (XSSFWorkbook wb = ouvrir(service.genererGrandLivre(null, null, du, au))) {
            Sheet sh = wb.getSheet("Grand livre de stock");
            assertThat(sh).isNotNull();
            assertThat(texte(sh, 0, 0)).isEqualTo("MBSC SARL");
            assertThat(texte(sh, 2, 0)).isEqualTo("Période : du 01/01/2026 au 07/10/2026");
            assertThat(texte(sh, 3, 0)).isEqualTo("Article : Tous les articles");
            assertThat(texte(sh, 4, 0)).isEqualTo("Entrepôt : Tous les entrepôts");

            // En-têtes : « Article » porte le nom, « Code article » le code.
            assertThat(texte(sh, 6, 1)).isEqualTo("Article");
            assertThat(texte(sh, 6, 2)).isEqualTo("Code article");
            assertThat(texte(sh, 7, 1)).isEqualTo("Primus 55CL");
            assertThat(texte(sh, 7, 2)).isEqualTo("BRA-01");
            assertThat(texte(sh, 8, 1)).isEqualTo("Simba 55CL");
        }
    }

    @Test
    void les_dates_et_les_nombres_sont_de_vraies_valeurs_exactes() throws IOException {
        societe("MBSC SARL");
        when(stockService.grandLivreStock(null, null, null, null)).thenReturn(List.of(
            mouvement(1, LocalDate.of(2026, 10, 6), "BRA-01", "Primus 55CL", "ENT-01", "MS-2026-000001",
                "32", "0", "32", "1.1666666", "37.32")));

        try (XSSFWorkbook wb = ouvrir(service.genererGrandLivre(null, null, null, null))) {
            Sheet sh = wb.getSheet("Grand livre de stock");
            Cell date = sh.getRow(7).getCell(0);
            assertThat(date.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(date)).isTrue();
            assertThat(date.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 6));
            assertThat(sh.getRow(7).getCell(5).getNumericCellValue()).isEqualTo(32d);
            assertThat(sh.getRow(7).getCell(6).getNumericCellValue()).isZero();
            assertThat(sh.getRow(7).getCell(7).getNumericCellValue()).isEqualTo(32d);
            // Le CMP garde toute sa précision : seul le format n'en montre que deux décimales.
            assertThat(sh.getRow(7).getCell(8).getNumericCellValue()).isEqualTo(1.1666666d);
            assertThat(sh.getRow(7).getCell(9).getNumericCellValue()).isEqualTo(37.32d);
            assertThat(texte(sh, 2, 0)).startsWith("Période : depuis l'origine jusqu'au ");
        }
    }

    @Test
    void les_filtres_choisis_sont_transmis_et_rappeles_dans_le_classeur() throws IOException {
        societe("MBSC SARL");
        LocalDate du = LocalDate.of(2026, 3, 1);
        LocalDate au = LocalDate.of(2026, 3, 31);
        when(articleRepository.findById(5L)).thenReturn(Optional.of(
            Article.builder().code("BRA-01").libelle("Primus 55CL").build()));
        when(entrepotRepository.findById(2L)).thenReturn(Optional.of(
            Entrepot.builder().code("ENT-02").nom("Dépôt central").build()));
        when(stockService.grandLivreStock(5L, 2L, du, au)).thenReturn(List.of());

        try (XSSFWorkbook wb = ouvrir(service.genererGrandLivre(5L, 2L, du, au))) {
            Sheet sh = wb.getSheet("Grand livre de stock");
            assertThat(texte(sh, 2, 0)).isEqualTo("Période : du 01/03/2026 au 31/03/2026");
            assertThat(texte(sh, 3, 0)).isEqualTo("Article : BRA-01 — Primus 55CL");
            assertThat(texte(sh, 4, 0)).isEqualTo("Entrepôt : ENT-02 — Dépôt central");
            // Aucune ligne : l'en-tête reste, sans donnée.
            assertThat(sh.getLastRowNum()).isEqualTo(6);
        }
        verify(stockService).grandLivreStock(5L, 2L, du, au);
    }

    @Test
    void sans_nom_de_societe_l_en_tete_reste_neutre() throws IOException {
        when(parametresRepository.findAll()).thenReturn(List.of());
        when(stockService.grandLivreStock(null, null, null, null)).thenReturn(List.of());

        try (XSSFWorkbook wb = ouvrir(service.genererGrandLivre(null, null, null, null))) {
            assertThat(texte(wb.getSheet("Grand livre de stock"), 0, 0)).isEqualTo("Entité");
        }
    }

    // ---------------------------------------------------------------------
    // État du stock
    // ---------------------------------------------------------------------

    @Test
    void l_etat_du_stock_reprend_les_colonnes_de_l_ecran_avec_unite_et_stock_minimum() throws IOException {
        societe("MBSC SARL");
        when(stockService.etatStock()).thenReturn(List.of(
            niveau("BRA-01", "Primus 55CL", "bouteille", "ENT-01", "30", "1.17", "34.98", "10", false),
            niveau("EP-02", "Ciboulette", "kg", "ENT-01", "4.75", "1.5", "7.11", "5", true)));

        try (XSSFWorkbook wb = ouvrir(service.genererEtat())) {
            Sheet sh = wb.getSheet("État du stock");
            assertThat(sh).isNotNull();
            assertThat(texte(sh, 0, 0)).isEqualTo("MBSC SARL");
            assertThat(texte(sh, 5, 0)).isEqualTo("Code article");
            assertThat(texte(sh, 5, 1)).isEqualTo("Libellé");
            assertThat(texte(sh, 5, 6)).isEqualTo("Valeur (USD)");
            assertThat(texte(sh, 5, 8)).isEqualTo("Seuil");

            assertThat(texte(sh, 6, 0)).isEqualTo("BRA-01");
            assertThat(texte(sh, 6, 1)).isEqualTo("Primus 55CL");
            assertThat(texte(sh, 6, 2)).isEqualTo("bouteille");
            assertThat(sh.getRow(6).getCell(4).getNumericCellValue()).isEqualTo(30d);
            assertThat(texte(sh, 6, 8)).isEqualTo("OK");
            assertThat(texte(sh, 7, 8)).isEqualTo("Sous seuil");
            assertThat(sh.getRow(7).getCell(4).getNumericCellValue()).isEqualTo(4.75d);
        }
    }

    @Test
    void les_totaux_de_l_etat_du_stock_sont_des_formules_deja_calculees() throws IOException {
        societe("MBSC SARL");
        when(stockService.etatStock()).thenReturn(List.of(
            niveau("BRA-01", "Primus 55CL", "bouteille", "ENT-01", "30", "1.17", "34.98", "10", false),
            niveau("EP-02", "Ciboulette", "kg", "ENT-01", "4.75", "1.5", "7.11", "5", true),
            niveau("GT-01", "Sel", "kg", "ENT-01", "0.99", "0.25", "0.25", "1", true)));

        try (XSSFWorkbook wb = ouvrir(service.genererEtat())) {
            Sheet sh = wb.getSheet("État du stock");

            // Valeur totale en tête : SUM de la colonne « Valeur » (G7:G9), résultat déjà en cache.
            Cell valeur = sh.getRow(2).getCell(2);
            assertThat(texte(sh, 2, 0)).isEqualTo("Valeur totale du stock (USD)");
            assertThat(valeur.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(valeur.getCellFormula()).isEqualTo("SUM(G7:G9)");
            assertThat(valeur.getNumericCellValue()).isCloseTo(42.34d, within(1e-9));

            // Alertes : COUNTIF sur la colonne « Seuil ».
            Cell alertes = sh.getRow(3).getCell(2);
            assertThat(texte(sh, 3, 0)).isEqualTo("Articles sous le seuil");
            assertThat(alertes.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(alertes.getCellFormula()).isEqualTo("COUNTIF(I7:I9,\"Sous seuil\")");
            assertThat(alertes.getNumericCellValue()).isEqualTo(2d);

            // Pied de tableau.
            assertThat(texte(sh, 9, 0)).isEqualTo("TOTAL");
            Cell total = sh.getRow(9).getCell(6);
            assertThat(total.getCellFormula()).isEqualTo("SUM(G7:G9)");
            assertThat(total.getNumericCellValue()).isCloseTo(42.34d, within(1e-9));
        }
    }

    @Test
    void un_etat_du_stock_vide_donne_des_totaux_a_zero_sans_formule_invalide() throws IOException {
        societe("MBSC SARL");
        when(stockService.etatStock()).thenReturn(List.of());

        try (XSSFWorkbook wb = ouvrir(service.genererEtat())) {
            Sheet sh = wb.getSheet("État du stock");
            assertThat(sh.getRow(2).getCell(2).getNumericCellValue()).isZero();
            assertThat(sh.getRow(3).getCell(2).getNumericCellValue()).isZero();
            assertThat(texte(sh, 6, 0)).isEqualTo("TOTAL");
            assertThat(sh.getRow(6).getCell(6).getNumericCellValue()).isZero();
        }
    }
}

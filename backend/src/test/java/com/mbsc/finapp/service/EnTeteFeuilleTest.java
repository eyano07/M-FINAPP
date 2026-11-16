package com.mbsc.finapp.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** En-tête unique des feuilles du classeur des états financiers (demande du 15/11/2026). */
class EnTeteFeuilleTest {

    private static final EnTeteFeuille.Identite ID =
        new EnTeteFeuille.Identite("Société Essai", "A123", LocalDate.of(2026, 11, 15), 11);

    private static XSSFWorkbook modeleHarmonise() {
        XSSFWorkbook wb = LiasseOhada.ouvrirModele();
        EnTeteFeuille entete = new EnTeteFeuille(wb, ID);
        List<XSSFSheet> feuilles = new ArrayList<>();
        wb.forEach(f -> feuilles.add((XSSFSheet) f));
        feuilles.forEach(entete::harmoniserModele);
        return wb;
    }

    private static String texte(Sheet sh, int r, int c) {
        Row row = sh.getRow(r);
        return row == null || row.getCell(c) == null ? null : row.getCell(c).getStringCellValue();
    }

    /** Même gabarit sur chaque feuille : bandeau fusionné en ligne 1, entité/exercice en 2, identifiant/durée en 3. */
    private static void verifierGabarit(Sheet sh) {
        String nom = sh.getSheetName();
        Row titre = sh.getRow(0);
        assertThat(titre).as(nom).isNotNull();
        assertThat(titre.getHeightInPoints()).as(nom).isEqualTo(24f);
        XSSFCellStyle st = (XSSFCellStyle) titre.getCell(0).getCellStyle();
        assertThat(st.getFillForegroundColorColor().getARGBHex()).as(nom).endsWith(EnTeteFeuille.BLEU_BANDEAU);
        assertThat(st.getFont().getBold()).as(nom).isTrue();
        assertThat(titre.getCell(0).getStringCellValue()).as(nom).isNotBlank();

        int der = sh.getMergedRegions().stream()
            .filter(m -> m.getFirstRow() == 0 && m.getLastRow() == 0 && m.getFirstColumn() == 0)
            .mapToInt(CellRangeAddress::getLastColumn).max().orElse(0);
        int milieu = (der + 1) / 2;
        assertThat(texte(sh, 1, 0)).as(nom).startsWith("Société Essai");
        if (der > 0) {
            assertThat(texte(sh, 1, milieu)).as(nom).isEqualTo("Exercice clos le 15/11/2026");
            assertThat(texte(sh, 2, 0)).as(nom).isEqualTo("N° d'identification : A123");
            assertThat(texte(sh, 2, milieu)).as(nom).isEqualTo("Durée (en mois) : 11");
        }
    }

    @Test
    void toutesLesFeuillesDuModelePortentLeMemeEnTete() throws Exception {
        try (XSSFWorkbook wb = modeleHarmonise()) {
            for (Sheet sh : wb) {
                assertThat(sh.getPrintSetup().getFitHeight()).as(sh.getSheetName()).isEqualTo((short) 1);
                if (!EnTeteFeuille.PAGE_DE_GARDE.equals(sh.getSheetName())) {
                    verifierGabarit(sh);
                }
                // Plus aucun bandeau dessiné dans les trois premières lignes.
                XSSFDrawing d = ((XSSFSheet) sh).getDrawingPatriarch();
                if (d != null) {
                    for (CTTwoCellAnchor a : d.getCTDrawing().getTwoCellAnchorList()) {
                        assertThat(a.getFrom().getRow()).as(sh.getSheetName()).isGreaterThan(2);
                    }
                }
            }
            assertThat(texte(wb.getSheet("NOTE 13"), 0, 0)).isEqualTo("NOTE 13 : CAPITAL");
            // Zone imprimée limitée au cadre : la couverture s'arrête en colonne J.
            assertThat(wb.getPrintArea(wb.getSheetIndex("PAGE DE GARDE"))).endsWith("$A$1:$J$51");
            // La couverture n'a plus de bandeau « PAGE DE GARDE ».
            assertThat(texte(wb.getSheet("PAGE DE GARDE"), 0, 0)).isNullOrEmpty();
            assertThat(((XSSFSheet) wb.getSheet("PAGE DE GARDE")).getDrawingPatriarch()
                .getCTDrawing().sizeOfTwoCellAnchorArray()).isZero();
            assertThat(texte(wb.getSheet("FICHE R2"), 0, 0)).isEqualTo("FICHE R2");
            assertThat(texte(wb.getSheet("NOTE 27B"), 0, 0))
                .isEqualTo("NOTE 27B : EFFECTIFS, MASSE SALARIALE ET PERSONNEL EXTERIEUR");
            assertThat(texte(wb.getSheet("BILAN"), 0, 0)).isEqualTo("BILAN (MODÈLE PAYSAGE)");
        }
    }

    @Test
    void leCorpsDesFeuillesNeBougePas() throws Exception {
        try (XSSFWorkbook wb = modeleHarmonise()) {
            // Les renvois de LiasseOhada visent des lignes fixes : le corps reste en place.
            assertThat(texte(wb.getSheet("NOTE 7"), 6, 0)).isEqualTo("CLIENTS");
            assertThat(texte(wb.getSheet("BILAN"), 5, 2)).startsWith("B I L A N");
            assertThat(texte(wb.getSheet("FICHE R1"), 4, 1)).startsWith("Dénomination sociale");
            // CODES n'avait pas de zone d'en-tête : la liste descend sous l'en-tête.
            assertThat(texte(wb.getSheet("CODES"), 4, 0)).isEqualTo("Agriculture  vivrière");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        }
    }

    @Test
    void uneFeuilleDeLApplicationRecoitLeMemeGabarit() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Journal");
            int premiere = new EnTeteFeuille(wb, ID).poser(sh, "JOURNAL COMPTABLE", 8, "Toutes les écritures");
            assertThat(premiere).isEqualTo(4);
            verifierGabarit(sh);
            assertThat(texte(sh, 3, 0)).isEqualTo("Toutes les écritures");
            assertThat(sh.getMergedRegions()).contains(new CellRangeAddress(0, 0, 0, 8));
        }
    }

    @Test
    void piedDePageNumeroteSaufCouverture() throws Exception {
        try (XSSFWorkbook wb = modeleHarmonise()) {
            wb.forEach(EnTeteFeuille::piedDePage);
            assertThat(wb.getSheet("NOTE 13").getFooter().getRight()).contains("Page &P / &N");
            assertThat(wb.getSheet("PAGE DE GARDE").getFooter().getRight()).doesNotContain("&P");
        }
    }

    @Test
    void identiteTireeDesParametres() {
        var p = com.mbsc.finapp.domain.ParametresEntreprise.builder().nom("MBSC").nif("N1").build();
        EnTeteFeuille.Identite id = EnTeteFeuille.Identite.de(p, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 15));
        assertThat(id.denomination()).isEqualTo("MBSC");
        assertThat(id.dureeMois()).isEqualTo(11);
        assertThat(EnTeteFeuille.propre("NOTE 13 : CAPITAL  ")).isEqualTo("NOTE 13 : CAPITAL");
    }
}

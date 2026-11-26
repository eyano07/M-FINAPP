package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.parametrage.InformationsLiasse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Liasse OHADA du classeur des états financiers, remplie sur un petit plan de
 * comptes fictif : montants des notes, totaux par formule, renvois au BILAN et à
 * la note 34, fiches R1 à R4.
 */
class LiasseOhadaTest {

    /** Compte fictif : solde d'ouverture et mouvements de l'exercice (débit/crédit), mouvement net N-1. */
    private record C(String numero, TypeCompte type, double ouverture, double debit, double credit, double fluxN1) {
        BigDecimal cloture() { return BigDecimal.valueOf(ouverture + debit - credit); }
    }

    private static final List<C> COMPTES = List.of(
        new C("2441", TypeCompte.ACTIF, 78_372, 10_000, 0, 0),       // matériel
        new C("2451", TypeCompte.ACTIF, 28_428, 0, 0, 0),            // matériel de transport
        new C("28441", TypeCompte.ACTIF, -5_000, 0, 2_000, 0),       // amortissements du matériel
        new C("311", TypeCompte.ACTIF, 4_000, 6_000, 3_000, 0),      // marchandises
        new C("4111", TypeCompte.ACTIF, 1_500, 9_000, 7_000, 0),     // clients
        new C("4011", TypeCompte.PASSIF, -2_000, 5_000, 8_000, 0),   // fournisseurs
        new C("5211", TypeCompte.ACTIF, 1_000, 20_000, 12_000, 0),   // banque
        new C("5711", TypeCompte.ACTIF, 2_045, 3_000, 4_000, 0),     // caisse
        new C("1011", TypeCompte.PASSIF, -100_000, 0, 0, 0),         // capital
        new C("6011", TypeCompte.CHARGE, 0, 6_000, 0, 4_500),        // achats de marchandises
        new C("7011", TypeCompte.PRODUIT, 0, 0, 9_000, -7_000)       // ventes de marchandises
    );

    private static LiasseOhada.Soldes soldes(List<C> comptes) {
        return new LiasseOhada.Soldes() {
            @Override
            public BigDecimal solde(TypeCompte type, boolean ouverture, String... p) {
                return comptes.stream().filter(c -> (type == null || c.type() == type) && LiasseOhada.correspond(c.numero(), p))
                    .map(c -> ouverture ? BigDecimal.valueOf(c.ouverture()) : c.cloture())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            @Override
            public BigDecimal debits(String... p) {
                return comptes.stream().filter(c -> LiasseOhada.correspond(c.numero(), p))
                    .map(c -> BigDecimal.valueOf(c.debit())).reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            @Override
            public BigDecimal credits(String... p) {
                return comptes.stream().filter(c -> LiasseOhada.correspond(c.numero(), p))
                    .map(c -> BigDecimal.valueOf(c.credit())).reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            @Override
            public BigDecimal flux(boolean precedent, String... p) {
                return comptes.stream().filter(c -> LiasseOhada.correspond(c.numero(), p))
                    .map(c -> BigDecimal.valueOf(precedent ? c.fluxN1() : c.debit() - c.credit()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            @Override
            public boolean vide() {
                return comptes.isEmpty();
            }
        };
    }

    /** Feuilles de l'application auxquelles la liasse renvoie : un code par ligne en colonne A. */
    private static void feuilleApplication(XSSFWorkbook wb, String nom, String... codes) {
        Sheet sh = wb.createSheet(nom);
        for (int i = 0; i < codes.length; i++) {
            Row r = sh.createRow(5 + i);
            r.createCell(0).setCellValue(codes[i]);
            for (int c = 3; c <= 6; c++) {
                r.createCell(c).setCellValue(1000 * (i + 1) + c);
            }
        }
    }

    private static XSSFWorkbook genere(InformationsLiasse infos) {
        XSSFWorkbook wb = LiasseOhada.ouvrirModele();
        feuilleApplication(wb, "ACTIF", "AD", "AE", "AZ", "BA", "BB", "BG", "BK", "BT", "BU", "BZ");
        feuilleApplication(wb, "PASSIF", "CA", "CP", "DD", "DF", "DH", "DP", "DT", "DZ");
        feuilleApplication(wb, "RESULTAT", "TA", "XA", "XB", "XC", "XD", "XE", "XF", "XG", "XH", "XI");
        feuilleApplication(wb, "TFT", "ZA", "FA", "FN", "ZB", "ZC", "ZF");
        ParametresEntreprise p = ParametresEntreprise.builder().nom("Société Essai").nif("A123").rccm("CD/KIN/RCCM/1")
            .adresse("12 avenue du Port").telephone("+243 1").email("contact@essai.example").build();
        LiasseOhada.remplir(wb, new LiasseOhada.Contexte(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), p,
            infos, soldes(COMPTES), k -> soldes(List.of())));
        FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
        ev.evaluateAll();
        return wb;
    }

    private static Cell cell(XSSFWorkbook wb, String feuille, String adresse) {
        CellReference ref = new CellReference(adresse);
        Row r = wb.getSheet(feuille).getRow(ref.getRow());
        return r == null ? null : r.getCell(ref.getCol());
    }

    private static double num(XSSFWorkbook wb, String feuille, String adresse) {
        Cell c = cell(wb, feuille, adresse);
        if (c == null) {
            return 0;
        }
        return c.getCellType() == CellType.FORMULA ? c.getNumericCellValue() : c.getNumericCellValue();
    }

    private static String txt(XSSFWorkbook wb, String feuille, String adresse) {
        Cell c = cell(wb, feuille, adresse);
        if (c == null) {
            return "";
        }
        if (c.getCellType() == CellType.FORMULA) {
            return c.getCachedFormulaResultType() == CellType.STRING ? c.getStringCellValue() : "";
        }
        return c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
    }

    @Test
    void notesCalculeesDepuisLesSoldesAvecTotauxEnFormules() throws Exception {
        try (XSSFWorkbook wb = genere(InformationsLiasse.vide())) {
            // Note 3A : matériel 78 372 + 10 000 d'acquisitions ; total général par formule.
            assertThat(num(wb, "NOTE 3A", "B26")).isEqualTo(78_372);
            assertThat(num(wb, "NOTE 3A", "C26")).isEqualTo(10_000);
            assertThat(num(wb, "NOTE 3A", "H26")).isEqualTo(88_372);
            assertThat(num(wb, "NOTE 3A", "H27")).isEqualTo(28_428);
            assertThat(num(wb, "NOTE 3A", "H34")).isEqualTo(116_800);
            assertThat(cell(wb, "NOTE 3A", "H34").getCellType()).isEqualTo(CellType.FORMULA);
            // Note 3C : amortissements 5 000 + 2 000 de dotation.
            assertThat(num(wb, "NOTE 3C", "E25")).isEqualTo(7_000);
            // Note 6 : marchandises 7 000 (N) et 4 000 (N-1), variation de 75 %.
            assertThat(num(wb, "NOTE 6", "B20")).isEqualTo(7_000);
            assertThat(num(wb, "NOTE 6", "C20")).isEqualTo(4_000);
            assertThat(num(wb, "NOTE 6", "D20")).isEqualTo(75);
            assertThat(num(wb, "NOTE 6", "B24")).isEqualTo(7_000);
            // Note 7 : clients 3 500 ; Note 17 : fournisseurs 5 000 ; Note 11 : banque + caisse.
            assertThat(num(wb, "NOTE 7", "B24")).isEqualTo(3_500);
            assertThat(num(wb, "NOTE 17", "B15")).isEqualTo(5_000);
            assertThat(num(wb, "NOTE 11", "C12")).isEqualTo(9_000);
            assertThat(num(wb, "NOTE 11", "C21")).isEqualTo(1_045);
            assertThat(num(wb, "NOTE 11", "C24")).isEqualTo(10_045);
            assertThat(num(wb, "NOTE 11", "D24")).isEqualTo(3_045);
            // Notes de résultat : N et N-1.
            assertThat(num(wb, "NOTE 21", "B33")).isEqualTo(9_000);
            assertThat(num(wb, "NOTE 21", "C33")).isEqualTo(7_000);
            assertThat(num(wb, "NOTE 22", "B14")).isEqualTo(6_000);
            assertThat(num(wb, "NOTE 22", "C14")).isEqualTo(4_500);
            // Note 31 : exercice N renseigné, exercices antérieurs sans écriture laissés vides.
            assertThat(num(wb, "NOTE 31", "B11")).isEqualTo(100_000);
            assertThat(num(wb, "NOTE 31", "B18")).isEqualTo(9_000);
            assertThat(num(wb, "NOTE 31", "B22")).isEqualTo(3_000);
            // Note 13 : le capital remplace la formule du modèle ; aucune formule partagée ne subsiste.
            assertThat(num(wb, "NOTE 13", "F24")).isEqualTo(100_000);
            assertThat(cell(wb, "NOTE 13", "F24").getCellType()).isEqualTo(CellType.NUMERIC);
            for (Sheet sh : wb) {
                for (Row row : sh) {
                    for (Cell c : row) {
                        if (c.getCellType() == CellType.FORMULA) {
                            assertThat(((org.apache.poi.xssf.usermodel.XSSFCell) c).getCTCell().getF().isSetSi())
                                .as("formule partagée %s!%s", sh.getSheetName(), c.getAddress()).isFalse();
                        }
                    }
                }
            }
            // Note 3D : pas de cession comptabilisée, pas de moins-value fictive.
            assertThat(num(wb, "NOTE 3D", "G30")).isZero();
            // Fiche R2 sans activité saisie : tout le chiffre d'affaires en « Divers », total 100 %.
            assertThat(num(wb, "FICHE R2", "AH42")).isEqualTo(100);
            assertThat(txt(wb, "FICHE R2", "J5")).isEqualTo("Société Essai");
            assertThat(cell(wb, "NOTE 31", "C18") == null || cell(wb, "NOTE 31", "C18").getCellType() == CellType.BLANK).isTrue();
        }
    }

    @Test
    void bilanEtNote34RenvoientAuxFeuillesDeLApplication() throws Exception {
        try (XSSFWorkbook wb = genere(InformationsLiasse.vide())) {
            // BILAN ligne AE (13e ligne Excel) → ACTIF, ligne du code AE (7e ligne Excel).
            assertThat(cell(wb, "BILAN", "D12").getCellFormula()).isEqualTo("ACTIF!D7");
            assertThat(cell(wb, "BILAN", "F12").getCellFormula()).isEqualTo("D12-E12");
            // « BC » du modèle = « BG » de l'application.
            assertThat(cell(wb, "BILAN", "D29").getCellFormula()).isEqualTo("ACTIF!D11");
            assertThat(cell(wb, "BILAN", "K11").getCellFormula()).isEqualTo("PASSIF!D6");
            // Note 34 : chiffre d'affaires = RESULTAT, ligne XB (8e ligne Excel), colonnes E et F.
            assertThat(cell(wb, "NOTE 34", "C10").getCellFormula()).isEqualTo("RESULTAT!E8");
            assertThat(cell(wb, "NOTE 34", "D10").getCellFormula()).isEqualTo("RESULTAT!F8");
            assertThat(cell(wb, "NOTE 34", "C33").getCellFormula()).isEqualTo("TFT!E7");
            assertThat(cell(wb, "NOTE 34", "C40").getCellFormula()).isEqualTo("BILAN!K21");
            assertThat(cell(wb, "NOTE 34", "D40").getCellFormula()).isEqualTo("BILAN!L21");
            // Valeurs calculées : chaque case du BILAN rend bien la case visée de la feuille ACTIF (N et N-1).
            for (int l : new int[]{11, 12, 26, 27, 28, 29, 33, 37, 39}) {
                String cible = cell(wb, "BILAN", "G" + l).getCellFormula().replace("ACTIF!", "");
                assertThat(num(wb, "BILAN", "G" + l)).as("BILAN!G%d", l).isEqualTo(num(wb, "ACTIF", cible));
            }
        }
    }

    @Test
    void fichesRenseigneesEtR4Cochee() throws Exception {
        InformationsLiasse infos = new InformationsLiasse("République démocratique du Congo", "Direction générale des impôts",
            "CDI Kinshasa", "ESSAI", "BP 123", "", "Kinshasa", LocalDate.of(2027, 3, 31), null, 12,
            "Greffe de Kinshasa", "", "CNSS-1", "", "033002", "Bar et restauration", "", "Cabinet Exemple", "",
            true, "Jean Exemple", "Gérant", LocalDate.of(2027, 4, 2),
            List.of(new InformationsLiasse.Domiciliation("Banque Exemple", "0001-2")),
            "02", "1", "17", 1, 0, 2024, "PRIVE_NATIONAL",
            List.of(new InformationsLiasse.Activite("Restauration", "03300", new BigDecimal("60")),
                    new InformationsLiasse.Activite("Bar", "", new BigDecimal("30"))),
            List.of(new InformationsLiasse.Dirigeant("EXEMPLE", "Jean", "Gérant", "A1", "Kinshasa")),
            new ArrayList<>());
        try (XSSFWorkbook wb = genere(infos)) {
            assertThat(txt(wb, "PAGE DE GARDE", "A6")).isEqualTo("RÉPUBLIQUE DÉMOCRATIQUE DU CONGO");
            assertThat(txt(wb, "PAGE DE GARDE", "E25")).isEqualTo("Société Essai");
            assertThat(txt(wb, "FICHE R1", "B6")).isEqualTo("Société Essai");
            assertThat(txt(wb, "FICHE R1", "W8")).isEqualTo("Exercice clos le 31/12/2026");
            assertThat(txt(wb, "FICHE R1", "W12")).isEqualTo("31/03/2027");
            assertThat(txt(wb, "FICHE R1", "W14")).isEqualTo("31/12/2025");
            assertThat(txt(wb, "FICHE R1", "J16")).isEqualTo("CD/KIN/RCCM/1");
            assertThat(txt(wb, "FICHE R1", "Q25")).isEqualTo("contact@essai.example");
            assertThat(txt(wb, "FICHE R1", "AP48")).isEqualTo("X");
            assertThat(txt(wb, "FICHE R1", "AB55")).isEqualTo("Banque Exemple");
            // Fiche R2 : un chiffre par case ; chiffre d'affaires réparti 60 / 30 / 10 (divers).
            assertThat(txt(wb, "FICHE R2", "R10") + txt(wb, "FICHE R2", "S10")).isEqualTo("02");
            assertThat(txt(wb, "FICHE R2", "R14") + txt(wb, "FICHE R2", "S14")).isEqualTo("17");
            assertThat(txt(wb, "FICHE R2", "AJ12")).isEqualTo("X");
            assertThat(num(wb, "FICHE R2", "Y30")).isEqualTo(5_400);
            assertThat(num(wb, "FICHE R2", "Y34")).isEqualTo(2_700);
            assertThat(num(wb, "FICHE R2", "Y40")).isEqualTo(900);
            assertThat(num(wb, "FICHE R2", "Y42")).isEqualTo(9_000);
            assertThat(num(wb, "FICHE R2", "AH42")).isEqualTo(100);
            assertThat(txt(wb, "FICHE R3", "B12")).isEqualTo("EXEMPLE");
            // Fiche R4 : note 6 (stocks) applicable, note 3B (crédit-bail) non applicable.
            assertThat(txt(wb, "FICHE R4", "C17")).isEqualTo("X");
            assertThat(txt(wb, "FICHE R4", "D10")).isEqualTo("X");
            assertThat(txt(wb, "FICHE R4", "C8")).isEqualTo("X");
        }
    }
}

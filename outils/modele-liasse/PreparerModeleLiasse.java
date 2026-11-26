import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Prépare le modèle de liasse OHADA embarqué par le backend
 * (backend/src/main/resources/modeles/liasse-ohada.xlsx) à partir d'un classeur
 * de liasse rempli à la main.
 *
 * <p>Le modèle garde la mise en page (bandeaux, couleurs, cadres, largeurs,
 * hauteurs, fusions) de la page de garde, des fiches R1 à R4, du BILAN, des
 * notes 1 à 36 et de la feuille CODES. Il perd : les feuilles que l'application
 * produit elle-même (ACTIF, PASSIF, RESULTAT, TFT), les montants saisis, les
 * renvois vers les feuilles supprimées et le nom de l'entité d'origine.</p>
 *
 * <p>Usage (JDK 21, jars POI sur le classpath) :
 * {@code java -cp <jars> PreparerModeleLiasse.java source.xlsx modele.xlsx}
 * puis {@code python3 sans_calcchain.py modele.xlsx} pour retirer la chaîne de
 * calcul, qu'Excel reconstruit à l'ouverture.</p>
 */
public class PreparerModeleLiasse {

    private static final Set<String> SUPPRIMEES = Set.of("ACTIF", "PASSIF", "RESULTAT", "TFT", "Feuil1");

    public static void main(String[] args) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new FileInputStream(args[0]))) {
            for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--) {
                if (SUPPRIMEES.contains(wb.getSheetName(i).trim())) {
                    wb.removeSheetAt(i);
                }
            }
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                String nom = wb.getSheetName(i).trim();
                if (nom.equals("NOTES 15B")) {
                    nom = "NOTE 15B";
                }
                wb.setSheetName(i, nom);
            }

            // Noms définis : les zones d'impression restent, les constantes
            // d'identité (_soNom, _ExerClos…) sont vidées, l'application les pose.
            List<XSSFName> noms = new ArrayList<>(wb.getAllNames());
            for (XSSFName n : noms) {
                String f = n.getRefersToFormula();
                if (f != null && f.startsWith("\"")) {
                    n.setRefersToFormula("\" \"");
                }
            }

            for (Sheet sh : wb) {
                String nom = sh.getSheetName();
                boolean donnees = nom.startsWith("NOTE") && !nom.equals("NOTE 36") || nom.equals("BILAN");
                for (Row row : sh) {
                    for (Cell c : row) {
                        nettoyer(nom, donnees, c);
                    }
                }
            }

            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (Sheet sh : wb) {
                for (Row row : sh) {
                    for (Cell c : row) {
                        if (c.getCellType() == CellType.FORMULA) {
                            try {
                                ev.evaluateFormulaCell(c);
                            } catch (RuntimeException e) {
                                System.err.println("Non évaluée " + sh.getSheetName() + "!" + c.getAddress() + " : " + e.getMessage());
                            }
                        }
                    }
                }
            }
            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            try (FileOutputStream out = new FileOutputStream(args[1])) {
                wb.write(out);
            }
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                System.out.println(i + " " + wb.getSheetName(i));
            }
        }
    }

    private static void nettoyer(String feuille, boolean donnees, Cell c) {
        switch (c.getCellType()) {
            case NUMERIC -> {
                if (donnees || feuille.startsWith("FICHE R1")) {
                    c.setBlank();
                }
            }
            case FORMULA -> {
                String f;
                try {
                    f = c.getCellFormula();
                } catch (FormulaParseException e) {
                    c.setBlank();
                    return;
                }
                if (f.contains("!")) {
                    c.setBlank();
                }
            }
            case STRING -> {
                String v = c.getStringCellValue();
                if (v.toUpperCase().contains("MBSC")) {
                    c.setBlank();
                }
            }
            default -> { }
        }
    }
}

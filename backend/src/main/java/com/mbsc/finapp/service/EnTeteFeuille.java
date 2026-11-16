package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTDrawing;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * En-tête unique des feuilles du classeur des états financiers (demande du 15/11/2026).
 *
 * <p>Le modèle de liasse de l'utilisateur dessinait chaque titre dans une forme flottante — bandeau ombré, plus
 * ou moins large, décalé d'une feuille à l'autre — et plaçait « Exercice clos le » et « Durée » à des colonnes
 * différentes ; les feuilles de l'application avaient encore un autre en-tête. Toutes les feuilles portent
 * désormais le même gabarit, en cellules (lisible par tout tableur, imprimé à coup sûr) :</p>
 * <pre>
 *  ligne 1 : bandeau titre — bleu, blanc gras, fusionné sur toute la largeur imprimée de la feuille
 *  ligne 2 : dénomination de l'entité (gauche)            Exercice clos le jj/mm/aaaa (droite)
 *  ligne 3 : N° d'identification (gauche)                 Durée (en mois) : n (droite)
 *  ligne 4 : sous-titre propre à la feuille (facultatif)
 * </pre>
 */
final class EnTeteFeuille {

    /** Identité de l'entité et de l'exercice, commune à toutes les feuilles. */
    record Identite(String denomination, String nif, LocalDate exerciceClos, long dureeMois) {

        static Identite de(ParametresEntreprise p, LocalDate du, LocalDate au) {
            String nom = "Entité";
            String nif = "";
            if (p != null) {
                String complet = p.getNomComplet() == null ? "" : p.getNomComplet().trim();
                String court = p.getNom() == null ? "" : p.getNom().trim();
                nom = !complet.isEmpty() ? complet : (!court.isEmpty() ? court : nom);
                nif = p.getNif() == null ? "" : p.getNif().trim();
            }
            long duree = ChronoUnit.MONTHS.between(du.withDayOfMonth(1), au.withDayOfMonth(1)) + 1;
            return new Identite(nom, nif, au, duree);
        }
    }

    /** Lignes (base 0) occupées par l'en-tête. */
    static final int LIGNE_TITRE = 0, LIGNE_ENTITE = 1, LIGNE_DUREE = 2, LIGNE_SOUS_TITRE = 3;
    /** Bleu du bandeau : celui des bandeaux « NOTE 13 », « NOTE 27B » du modèle. */
    static final String BLEU_BANDEAU = "1F4E79";

    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float HAUTEUR_BANDEAU = 24f, HAUTEUR_LIGNE = 15f, HAUTEUR_INTERCALAIRE = 6f;

    /** Titres des feuilles du modèle sans bandeau dessiné, ou dont le titre du modèle est vide ou fautif. */
    private static final Map<String, String> TITRES_IMPOSES = Map.of(
        "BILAN", "BILAN (MODÈLE PAYSAGE)",
        "CODES", "CODES DES ACTIVITÉS ÉCONOMIQUES",
        "NOTE 36", "NOTE 36 : TABLE DES CODES");

    private final XSSFWorkbook wb;
    private final Identite id;
    private final CellStyle bandeau, entite, info, infoDroite, sousTitre;

    EnTeteFeuille(XSSFWorkbook wb, Identite id) {
        this.wb = wb;
        this.id = id;

        XSSFFont fBandeau = police(12, true, false, "FFFFFF");
        bandeau = wb.createCellStyle();
        bandeau.setFont(fBandeau);
        ((XSSFCellStyle) bandeau).setFillForegroundColor(couleur(BLEU_BANDEAU));
        bandeau.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bandeau.setAlignment(HorizontalAlignment.CENTER);
        bandeau.setVerticalAlignment(VerticalAlignment.CENTER);
        bandeau.setBorderBottom(BorderStyle.MEDIUM);
        ((XSSFCellStyle) bandeau).setBottomBorderColor(couleur("0F2B45"));

        entite = style(police(10, true, false, "000000"), HorizontalAlignment.LEFT);
        info = style(police(10, false, false, "000000"), HorizontalAlignment.LEFT);
        infoDroite = style(police(10, false, false, "000000"), HorizontalAlignment.RIGHT);
        sousTitre = style(police(9, false, true, "595959"), HorizontalAlignment.LEFT);
    }

    // ------------------------------------------------------------------
    // Feuilles construites par l'application
    // ------------------------------------------------------------------

    /**
     * Pose l'en-tête sur les lignes 1 à 3 (et le sous-titre en ligne 4) d'une feuille.
     *
     * @param derniereColonne dernière colonne (base 0) de la zone imprimée : le bandeau s'y arrête
     * @return la première ligne libre (base 0) sous l'en-tête
     */
    int poser(Sheet sh, String titre, int derniereColonne, String sousTitreFeuille) {
        int fin = sousTitreFeuille == null ? LIGNE_DUREE : LIGNE_SOUS_TITRE;
        retirerFusions(sh, 0, fin);
        int der = Math.max(0, derniereColonne);
        int milieu = (der + 1) / 2;

        Row t = nouvelleLigne(sh, LIGNE_TITRE, HAUTEUR_BANDEAU);
        for (int c = 0; c <= der; c++) {
            t.createCell(c).setCellStyle(bandeau);
        }
        t.getCell(0).setCellValue(titre == null ? "" : titre);
        fusionner(sh, LIGNE_TITRE, 0, der);

        String exercice = "Exercice clos le " + id.exerciceClos().format(DATE_FR);
        String duree = "Durée (en mois) : " + id.dureeMois();
        String identification = id.nif().isEmpty() ? "" : "N° d'identification : " + id.nif();
        Row e = nouvelleLigne(sh, LIGNE_ENTITE, HAUTEUR_LIGNE);
        Row d = nouvelleLigne(sh, LIGNE_DUREE, HAUTEUR_LIGNE);
        if (der == 0) {
            // Feuille d'une seule colonne : les deux informations partagent la cellule.
            texte(e, 0, id.denomination() + "  —  " + exercice, entite);
            texte(d, 0, (identification.isEmpty() ? "" : identification + "  —  ") + duree, info);
        } else {
            texte(e, 0, id.denomination(), entite);
            texte(e, milieu, exercice, infoDroite);
            texte(d, 0, identification, info);
            texte(d, milieu, duree, infoDroite);
            for (Row r : List.of(e, d)) {
                fusionner(sh, r.getRowNum(), 0, milieu - 1);
                fusionner(sh, r.getRowNum(), milieu, der);
            }
        }

        if (sousTitreFeuille != null) {
            Row st = nouvelleLigne(sh, LIGNE_SOUS_TITRE, HAUTEUR_LIGNE);
            texte(st, 0, sousTitreFeuille, sousTitre);
            fusionner(sh, LIGNE_SOUS_TITRE, 0, der);
        }
        return fin + 1;
    }

    // ------------------------------------------------------------------
    // Feuilles recopiées du modèle de liasse
    // ------------------------------------------------------------------

    /**
     * Remplace l'en-tête d'une feuille du modèle par le gabarit commun : le bandeau dessiné en haut de la feuille
     * est retiré (son texte devient le titre), les lignes d'identité du modèle (dénomination, identifiant,
     * « Exercice clos le », « Durée ») sont effacées puis reposées aux positions communes. Les lignes de la zone
     * d'en-tête au-delà de la troisième deviennent un intercalaire fin, pour que le corps de la feuille ne bouge
     * pas — les formules et renvois du classeur restent donc exacts.
     */
    void harmoniserModele(XSSFSheet sh) {
        String titre = retirerBandeau(sh);
        unePageParFeuille(sh);
        if (PAGE_DE_GARDE.equals(sh.getSheetName())) {
            // Couverture de la liasse : ni bandeau ni en-tête (demande du 15/11/2026), elle porte déjà
            // la dénomination et l'exercice.
            zoneImpression(sh, derniereColonneUtile(sh, -1));
            return;
        }
        if ("CODES".equals(sh.getSheetName())) {
            // Liste de référence sans zone d'en-tête : on lui en fait une en descendant le tableau.
            Row a1 = sh.getRow(0);
            if (a1 != null && a1.getCell(0) != null && a1.getCell(0).getCellType() == CellType.STRING) {
                titre = titre == null ? propre(a1.getCell(0).getStringCellValue()) : titre;
                sh.removeRow(a1);
            }
            if (sh.getLastRowNum() >= 1) {
                sh.shiftRows(1, sh.getLastRowNum(), LIGNE_SOUS_TITRE);
            }
            int der = derniereColonneUtile(sh, LIGNE_SOUS_TITRE);
            poser(sh, titreOuDefaut(sh, titre), der, null);
            nouvelleLigne(sh, LIGNE_SOUS_TITRE, HAUTEUR_INTERCALAIRE);
            zoneImpression(sh, der);
            return;
        }

        int ligneNom = ligneDuNom(sh, "_sonom");
        int ligneExercice = ligneDuNom(sh, "_exerclos");
        int finZone;
        if (ligneNom >= 0 && (ligneExercice < 0 || ligneExercice == ligneNom)) {
            finZone = ligneNom + 1;
        } else {
            // Fiche R1 : l'identité fait partie du formulaire ; seule la zone du bandeau est reprise.
            finZone = Math.max(LIGNE_DUREE, bandeauFin);
        }
        finZone = Math.max(finZone, LIGNE_DUREE);
        if (titre == null) {
            titre = premierTexte(sh, finZone);
        }
        int der = derniereColonneUtile(sh, finZone);

        retirerFusions(sh, 0, finZone);
        for (int r = 0; r <= finZone; r++) {
            Row row = sh.getRow(r);
            if (row != null) {
                sh.removeRow(row);
            }
        }
        poser(sh, titreOuDefaut(sh, titre), der, null);
        for (int r = LIGNE_DUREE + 1; r <= finZone; r++) {
            nouvelleLigne(sh, r, HAUTEUR_INTERCALAIRE);
        }
        zoneImpression(sh, der);
    }

    /**
     * Zone imprimée = colonnes A à la dernière colonne utile (celle où s'arrête le bandeau), toutes les lignes.
     * Le modèle met en forme des colonnes jusqu'à HU : sans zone d'impression, LibreOffice les comptait dans
     * la largeur à faire tenir sur la page et réduisait la feuille (couverture rétrécie, pages presque vides).
     */
    private void zoneImpression(Sheet sh, int derniereColonne) {
        wb.setPrintArea(wb.getSheetIndex(sh), 0, Math.max(0, derniereColonne), 0, Math.max(0, sh.getLastRowNum()));
        sh.setHorizontallyCenter(true);
    }

    static final String PAGE_DE_GARDE = "PAGE DE GARDE";

    /**
     * Une feuille du modèle = une page imprimée. Plusieurs feuilles ont, sous leur tableau, des lignes vides mais
     * encadrées : imprimées « une page en largeur, hauteur libre », elles sortaient en une seconde page presque
     * blanche (pages 2, 20 et 23 du PDF du 15/11/2026).
     */
    private static void unePageParFeuille(Sheet sh) {
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 1);
    }

    /**
     * Pied de page de toutes les feuilles sauf la couverture : nom de la feuille à gauche, « Page n / total » à
     * droite. Les numéros courent sur tout le document quand le classeur entier est imprimé ou converti en PDF.
     */
    static void piedDePage(Sheet sh) {
        if (PAGE_DE_GARDE.equals(sh.getSheetName())) {
            return;
        }
        // Place du pied de page : certaines feuilles du modèle ont une marge du bas nulle (NOTE 1), ce qui
        // supprimait le numéro de leur page.
        sh.setMargin(Sheet.FooterMargin, 0.2);
        sh.setMargin(Sheet.BottomMargin, Math.max(sh.getMargin(Sheet.BottomMargin), 0.5));
        sh.setMargin(Sheet.TopMargin, Math.max(sh.getMargin(Sheet.TopMargin), 0.35));
        sh.getFooter().setLeft("&8&A");
        sh.getFooter().setCenter("");
        sh.getFooter().setRight("&8Page &P / &N");
    }

    /** Dernière ligne (base 0) couverte par le bandeau retiré lors du dernier appel. */
    private int bandeauFin = LIGNE_DUREE;

    /**
     * Retire les formes ancrées dans les trois premières lignes (le bandeau titre du modèle) et renvoie le texte
     * de la première qui en porte un.
     */
    private String retirerBandeau(XSSFSheet sh) {
        bandeauFin = LIGNE_DUREE;
        XSSFDrawing dessin = sh.getDrawingPatriarch();
        if (dessin == null) {
            return null;
        }
        CTDrawing ct = dessin.getCTDrawing();
        String titre = null;
        for (int i = ct.sizeOfTwoCellAnchorArray() - 1; i >= 0; i--) {
            CTTwoCellAnchor a = ct.getTwoCellAnchorArray(i);
            // Forme simple ou groupe de formes (rectangle ombré + zone de texte) : jamais une image ni un graphique.
            if (a.getFrom() == null || a.getFrom().getRow() > LIGNE_DUREE || (a.getSp() == null && a.getGrpSp() == null)) {
                continue;
            }
            String texte = propre(texteDe(a));
            if (!texte.isEmpty()) {
                titre = texte;
            }
            bandeauFin = Math.max(bandeauFin, a.getTo() == null ? LIGNE_DUREE : a.getTo().getRow());
            ct.removeTwoCellAnchor(i);
        }
        return titre;
    }

    /** Texte des runs « a:t » de l'ancre, formes groupées comprises, paragraphes séparés d'une espace. */
    private static String texteDe(CTTwoCellAnchor a) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = PARAGRAPHE_OU_TEXTE.matcher(a.xmlText());
        while (m.find()) {
            sb.append(m.group(1) == null ? " " : m.group(1));
        }
        return sb.toString()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
    }

    private static final java.util.regex.Pattern PARAGRAPHE_OU_TEXTE =
        java.util.regex.Pattern.compile("<(?:\\w+:)?t(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?t>|</(?:\\w+:)?p>");

    /** Ligne (base 0) de la première cellule « =_soNom » / « =_ExerClos » des dix premières lignes, sinon -1. */
    private static int ligneDuNom(Sheet sh, String nomMinuscule) {
        for (int r = 0; r <= 10; r++) {
            Row row = sh.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell c : row) {
                String f = c.getCellType() == CellType.FORMULA ? c.getCellFormula()
                    : c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
                if (f.trim().replaceFirst("^=", "").toLowerCase(Locale.ROOT).equals(nomMinuscule)) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static String premierTexte(Sheet sh, int finZone) {
        for (int r = 0; r <= finZone; r++) {
            Row row = sh.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell c : row) {
                if (c.getCellType() == CellType.STRING && !propre(c.getStringCellValue()).isEmpty()) {
                    return propre(c.getStringCellValue());
                }
            }
        }
        return null;
    }

    private static String titreOuDefaut(Sheet sh, String titre) {
        String impose = TITRES_IMPOSES.get(sh.getSheetName());
        if (impose != null) {
            return impose;
        }
        return titre == null || titre.isBlank() ? sh.getSheetName() : titre;
    }

    /**
     * Dernière colonne (base 0) portant une valeur, une formule ou une fusion sous la zone d'en-tête : c'est la
     * largeur de la partie imprimée de la feuille, à laquelle le bandeau s'aligne.
     */
    static int derniereColonneUtile(Sheet sh, int finZone) {
        int der = 0;
        for (Row row : sh) {
            if (row.getRowNum() <= finZone) {
                continue;
            }
            for (Cell c : row) {
                boolean utile = switch (c.getCellType()) {
                    case STRING -> !c.getStringCellValue().isBlank();
                    case NUMERIC, FORMULA, BOOLEAN -> true;
                    default -> false;
                };
                if (utile) {
                    der = Math.max(der, c.getColumnIndex());
                }
            }
        }
        for (CellRangeAddress m : sh.getMergedRegions()) {
            if (m.getFirstRow() > finZone) {
                der = Math.max(der, m.getLastColumn());
            }
        }
        return der;
    }

    /** Texte d'un bandeau : espaces insécables et blancs multiples ramenés à une espace, « : » espacé. */
    static String propre(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(' ', ' ').replaceAll("\\s*:\\s*", " : ").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    private static void retirerFusions(Sheet sh, int de, int a) {
        List<Integer> aRetirer = new ArrayList<>();
        for (int i = 0; i < sh.getNumMergedRegions(); i++) {
            CellRangeAddress m = sh.getMergedRegion(i);
            if (m.getFirstRow() <= a && m.getLastRow() >= de) {
                aRetirer.add(i);
            }
        }
        sh.removeMergedRegions(aRetirer);
    }

    private static void fusionner(Sheet sh, int ligne, int de, int a) {
        if (a > de) {
            sh.addMergedRegion(new CellRangeAddress(ligne, ligne, de, a));
        }
    }

    private static Row nouvelleLigne(Sheet sh, int r, float hauteur) {
        Row row = sh.getRow(r);
        if (row != null) {
            sh.removeRow(row);
        }
        row = sh.createRow(r);
        row.setHeightInPoints(hauteur);
        return row;
    }

    private static void texte(Row row, int col, String valeur, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(valeur);
        c.setCellStyle(style);
    }

    private CellStyle style(XSSFFont police, HorizontalAlignment alignement) {
        CellStyle st = wb.createCellStyle();
        st.setFont(police);
        st.setAlignment(alignement);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        return st;
    }

    private XSSFFont police(int taille, boolean gras, boolean italique, String couleurHex) {
        XSSFFont f = wb.createFont();
        f.setFontName("Arial");
        f.setFontHeightInPoints((short) taille);
        f.setBold(gras);
        f.setItalic(italique);
        f.setColor(couleur(couleurHex));
        return f;
    }

    private static XSSFColor couleur(String hex) {
        return new XSSFColor(new byte[]{
            (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
    }
}

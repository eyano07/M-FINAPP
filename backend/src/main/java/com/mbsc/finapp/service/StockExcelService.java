package com.mbsc.finapp.service;

import com.mbsc.finapp.dto.logistique.StockGrandLivreResponse;
import com.mbsc.finapp.dto.logistique.StockNiveauResponse;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports Excel du module stock : grand livre de stock et état du stock.
 *
 * <p>Chaque classeur reprend exactement les lignes que l'écran affiche pour les
 * mêmes filtres : les données viennent des mêmes méthodes de {@link StockService}
 * que les écrans, donc de la même sélection de lignes et des mêmes contrôles de
 * rôle (un rôle qui ne peut pas consulter le stock ne peut pas non plus
 * l'exporter).</p>
 *
 * <p>Les largeurs de colonnes sont fixées explicitement, sans
 * {@code Sheet.autoSizeColumn} : celui-ci s'appuie sur le rendu de police AWT,
 * indisponible sur l'image d'exécution Alpine (voir
 * {@code EtatsFinanciersExcelService}). Les totaux sont des formules, qui suivent
 * donc une correction faite dans Excel ; leur résultat est aussi calculé à
 * l'écriture pour que les aperçus qui ne recalculent pas (messagerie, mobile)
 * affichent des valeurs.</p>
 */
@Service
@RequiredArgsConstructor
public class StockExcelService {

    private static final String FORMAT_QUANTITE = "#,##0.00;[RED]-#,##0.00";
    /** Entrées et sorties : le zéro s'affiche « — », comme à l'écran. */
    private static final String FORMAT_MOUVEMENT = "#,##0.00;[RED]-#,##0.00;\"—\"";
    private static final String FORMAT_DATE = "dd/mm/yyyy";
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Libellés de la colonne « Seuil » : le décompte des alertes cherche le second. */
    static final String SEUIL_OK = "OK";
    static final String SEUIL_ALERTE = "Sous seuil";

    private final StockService stockService;
    private final ArticleRepository articleRepository;
    private final EntrepotRepository entrepotRepository;
    private final ParametresEntrepriseRepository parametresRepository;

    // ---------------------------------------------------------------------
    // Grand livre de stock
    // ---------------------------------------------------------------------

    /**
     * Mouvements valorisés, avec les filtres de l'écran (article, entrepôt,
     * période). Un filtre absent ({@code null}) ne restreint rien.
     */
    @Transactional(readOnly = true)
    public byte[] genererGrandLivre(Long articleId, Long entrepotId, LocalDate du, LocalDate au) {
        List<StockGrandLivreResponse> lignes = stockService.grandLivreStock(articleId, entrepotId, du, au);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            Sheet sh = wb.createSheet("Grand livre de stock");

            int r = 0;
            texte(sh.createRow(r++), 0, nomSociete(), s.titre);
            texte(sh.createRow(r++), 0, "Grand livre de stock — mouvements valorisés par article et entrepôt", s.sousTitre);
            texte(sh.createRow(r++), 0, "Période : " + libellePeriode(du, au), s.meta);
            texte(sh.createRow(r++), 0, "Article : " + libelleArticle(articleId), s.meta);
            texte(sh.createRow(r++), 0, "Entrepôt : " + libelleEntrepot(entrepotId), s.meta);
            r++;

            String[] entetes = {
                "Date", "Article", "Code article", "Entrepôt", "Mouvement",
                "Entrée", "Sortie", "Solde qté", "CMP", "Valeur stock",
            };
            int ligneEntete = r;
            enTete(sh.createRow(r++), entetes, s);

            for (StockGrandLivreResponse l : lignes) {
                Row row = sh.createRow(r++);
                date(row, 0, l.dateEcriture(), s.date);
                texte(row, 1, l.articleLibelle(), s.texte);
                texte(row, 2, l.articleCode(), s.texte);
                texte(row, 3, l.entrepotCode(), s.texte);
                texte(row, 4, l.mouvementReference(), s.texte);
                nombre(row, 5, l.qteEntree(), s.mouvement);
                nombre(row, 6, l.qteSortie(), s.mouvement);
                nombre(row, 7, l.qteApres(), s.quantite);
                nombre(row, 8, l.valeurUnitaire(), s.quantite);
                nombre(row, 9, l.valeurApres(), s.quantite);
            }

            largeurs(sh, 12, 34, 14, 12, 20, 12, 12, 12, 12, 16);
            sh.createFreezePane(0, ligneEntete + 1);
            sh.setAutoFilter(new CellRangeAddress(ligneEntete, Math.max(ligneEntete, r - 1), 0, entetes.length - 1));
            miseEnPage(sh, ligneEntete);
            return ecrire(wb, "Impossible de générer le grand livre de stock");
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le grand livre de stock", e);
        }
    }

    // ---------------------------------------------------------------------
    // État du stock
    // ---------------------------------------------------------------------

    /** Quantités et valorisation au coût moyen pondéré, tel que l'écran « État du stock » les affiche. */
    @Transactional(readOnly = true)
    public byte[] genererEtat() {
        List<StockNiveauResponse> niveaux = stockService.etatStock();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            Sheet sh = wb.createSheet("État du stock");

            int r = 0;
            texte(sh.createRow(r++), 0, nomSociete(), s.titre);
            texte(sh.createRow(r++), 0, "État du stock — quantités et valorisation au coût moyen pondéré (CMP)", s.sousTitre);
            int ligneValeurTotale = r++;
            int ligneAlertes = r++;
            r++;

            String[] entetes = {
                "Code article", "Libellé", "Unité", "Entrepôt", "Quantité", "CMP", "Valeur (USD)", "Stock minimum", "Seuil",
            };
            int ligneEntete = r;
            enTete(sh.createRow(r++), entetes, s);

            int premiere = r;
            for (StockNiveauResponse n : niveaux) {
                Row row = sh.createRow(r++);
                texte(row, 0, n.articleCode(), s.texte);
                texte(row, 1, n.articleLibelle(), s.texte);
                texte(row, 2, n.uniteMesure(), s.texte);
                texte(row, 3, n.entrepotCode(), s.texte);
                nombre(row, 4, n.quantite(), s.quantite);
                nombre(row, 5, n.coutMoyen(), s.quantite);
                nombre(row, 6, n.valeurTotale(), s.quantite);
                nombre(row, 7, n.stockMin(), s.quantite);
                texte(row, 8, n.sousSeuil() ? SEUIL_ALERTE : SEUIL_OK, s.centre);
            }
            int derniere = r - 1;

            // Total en pied de tableau et indicateurs de tête : des formules sur le tableau, pour
            // qu'ils restent justes si l'utilisateur corrige une valeur dans Excel.
            Row total = sh.createRow(r);
            texte(total, 0, "TOTAL", s.totalLabel);
            for (int c = 1; c < entetes.length; c++) {
                texte(total, c, "", s.totalLabel);
            }
            sommeOuZero(total, 6, premiere, derniere, s.totalMontant);

            // Libellé sur deux colonnes (A:B), valeur en C : la colonne A seule est trop étroite pour le texte.
            Row valeur = sh.createRow(ligneValeurTotale);
            texte(valeur, 0, "Valeur totale du stock (USD)", s.indicateurLabel);
            sh.addMergedRegion(new CellRangeAddress(ligneValeurTotale, ligneValeurTotale, 0, 1));
            sommeOuZero(valeur, 2, premiere, derniere, 6, s.indicateur);
            Row alertes = sh.createRow(ligneAlertes);
            texte(alertes, 0, "Articles sous le seuil", s.indicateurLabel);
            sh.addMergedRegion(new CellRangeAddress(ligneAlertes, ligneAlertes, 0, 1));
            if (derniere < premiere) {
                nombre(alertes, 2, BigDecimal.ZERO, s.indicateurEntier);
            } else {
                Cell c = alertes.createCell(2);
                c.setCellFormula("COUNTIF(I" + (premiere + 1) + ":I" + (derniere + 1) + ",\"" + SEUIL_ALERTE + "\")");
                c.setCellStyle(s.indicateurEntier);
            }

            largeurs(sh, 16, 32, 16, 12, 12, 10, 14, 14, 12);
            sh.createFreezePane(0, ligneEntete + 1);
            sh.setAutoFilter(new CellRangeAddress(ligneEntete, Math.max(ligneEntete, derniere), 0, entetes.length - 1));
            miseEnPage(sh, ligneEntete);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            return ecrire(wb, "Impossible de générer l'état du stock");
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer l'état du stock", e);
        }
    }

    // ---------------------------------------------------------------------
    // Libellés des filtres
    // ---------------------------------------------------------------------

    private String libelleArticle(Long articleId) {
        if (articleId == null) {
            return "Tous les articles";
        }
        return articleRepository.findById(articleId)
            .map(a -> a.getCode() + " — " + a.getLibelle())
            .orElse("Article n° " + articleId);
    }

    private String libelleEntrepot(Long entrepotId) {
        if (entrepotId == null) {
            return "Tous les entrepôts";
        }
        return entrepotRepository.findById(entrepotId)
            .map(e -> e.getCode() + " — " + e.getNom())
            .orElse("Entrepôt n° " + entrepotId);
    }

    /** Même défaut que l'écran : sans borne de début, depuis l'origine ; sans borne de fin, jusqu'à aujourd'hui. */
    private static String libellePeriode(LocalDate du, LocalDate au) {
        String fin = DATE_FR.format(au == null ? LocalDate.now() : au);
        return du == null
            ? "depuis l'origine jusqu'au " + fin
            : "du " + DATE_FR.format(du) + " au " + fin;
    }

    /** Raison sociale pour l'en-tête, ou un libellé neutre à défaut. */
    private String nomSociete() {
        return parametresRepository.findAll().stream()
            .findFirst()
            .map(p -> p.getNom() == null ? "" : p.getNom())
            .filter(n -> !n.isBlank())
            .orElse("Entité");
    }

    // ---------------------------------------------------------------------
    // Aides de mise en forme
    // ---------------------------------------------------------------------

    private static void largeurs(Sheet sh, int... caracteres) {
        for (int c = 0; c < caracteres.length; c++) {
            sh.setColumnWidth(c, caracteres[c] * 256);
        }
    }

    /** Impression : paysage (tableaux larges), une page de large, en-tête répété sur chaque page. */
    private static void miseEnPage(Sheet sh, int ligneEntete) {
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 0);
        sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sh.getPrintSetup().setLandscape(true);
        sh.setRepeatingRows(new CellRangeAddress(ligneEntete, ligneEntete, -1, -1));
    }

    private static void enTete(Row row, String[] titres, Styles s) {
        for (int c = 0; c < titres.length; c++) {
            texte(row, c, titres[c], s.enTete);
        }
    }

    private static void texte(Row row, int col, String valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(valeur == null ? "" : valeur);
        cell.setCellStyle(style);
    }

    /** Date réelle (et non du texte) : triable et filtrable dans Excel. */
    private static void date(Row row, int col, LocalDate valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        if (valeur != null) {
            cell.setCellValue(valeur);
        }
        cell.setCellStyle(style);
    }

    /** Valeur exacte, sans arrondi : le format n'en affiche que deux décimales. */
    private static void nombre(Row row, int col, BigDecimal valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(valeur == null ? 0d : valeur.doubleValue());
        cell.setCellStyle(style);
    }

    private static void sommeOuZero(Row row, int col, int premiereLigne0, int derniereLigne0, CellStyle style) {
        sommeOuZero(row, col, premiereLigne0, derniereLigne0, col, style);
    }

    /** SUM d'une colonne du tableau, ou 0 s'il n'y a aucune ligne (POI refuse SUM(G8:G7)). */
    private static void sommeOuZero(Row row, int col, int premiereLigne0, int derniereLigne0,
                                    int colonneSommee, CellStyle style) {
        Cell cell = row.createCell(col);
        if (derniereLigne0 < premiereLigne0) {
            cell.setCellValue(0d);
        } else {
            String lettre = String.valueOf((char) ('A' + colonneSommee));
            cell.setCellFormula("SUM(" + lettre + (premiereLigne0 + 1) + ":" + lettre + (derniereLigne0 + 1) + ")");
        }
        cell.setCellStyle(style);
    }

    private static byte[] ecrire(XSSFWorkbook wb, String messageErreur) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(messageErreur, e);
        }
    }

    private static final class Styles {
        final CellStyle titre, sousTitre, meta, enTete, texte, centre, date, quantite, mouvement,
            totalLabel, totalMontant, indicateurLabel, indicateur, indicateurEntier;

        Styles(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();

            Font fTitre = wb.createFont();
            fTitre.setBold(true);
            fTitre.setFontHeightInPoints((short) 14);
            titre = wb.createCellStyle();
            titre.setFont(fTitre);

            Font fSousTitre = wb.createFont();
            fSousTitre.setBold(true);
            sousTitre = wb.createCellStyle();
            sousTitre.setFont(fSousTitre);

            Font fMeta = wb.createFont();
            fMeta.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            meta = wb.createCellStyle();
            meta.setFont(fMeta);

            Font fBlanc = wb.createFont();
            fBlanc.setBold(true);
            fBlanc.setColor(IndexedColors.WHITE.getIndex());
            enTete = wb.createCellStyle();
            enTete.setFont(fBlanc);
            enTete.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            enTete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            enTete.setAlignment(HorizontalAlignment.CENTER);
            enTete.setWrapText(true);

            texte = wb.createCellStyle();

            centre = wb.createCellStyle();
            centre.setAlignment(HorizontalAlignment.CENTER);

            date = wb.createCellStyle();
            date.setDataFormat(fmt.getFormat(FORMAT_DATE));
            date.setAlignment(HorizontalAlignment.LEFT);

            quantite = wb.createCellStyle();
            quantite.setDataFormat(fmt.getFormat(FORMAT_QUANTITE));
            quantite.setAlignment(HorizontalAlignment.RIGHT);

            mouvement = wb.createCellStyle();
            mouvement.setDataFormat(fmt.getFormat(FORMAT_MOUVEMENT));
            mouvement.setAlignment(HorizontalAlignment.RIGHT);

            Font fGras = wb.createFont();
            fGras.setBold(true);
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(fGras);
            totalLabel.setBorderTop(BorderStyle.THIN);

            totalMontant = wb.createCellStyle();
            totalMontant.cloneStyleFrom(quantite);
            totalMontant.setFont(fGras);
            totalMontant.setBorderTop(BorderStyle.THIN);

            indicateurLabel = wb.createCellStyle();
            indicateurLabel.setFont(fGras);

            indicateur = wb.createCellStyle();
            indicateur.cloneStyleFrom(quantite);
            indicateur.setFont(fGras);
            indicateur.setAlignment(HorizontalAlignment.LEFT);

            indicateurEntier = wb.createCellStyle();
            indicateurEntier.setDataFormat(fmt.getFormat("0"));
            indicateurEntier.setFont(fGras);
            indicateurEntier.setAlignment(HorizontalAlignment.LEFT);
        }
    }
}

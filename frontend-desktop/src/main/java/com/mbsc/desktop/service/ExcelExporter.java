package com.mbsc.desktop.service;

import com.mbsc.desktop.api.NoteDtos;
import com.mbsc.desktop.model.LocalEcriture;
import com.mbsc.desktop.model.LocalTransaction;
import com.mbsc.desktop.model.SensTransaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Utilitaire d'export vers Excel (.xlsx) via Apache POI.
 * Produit des fichiers avec en-tête coloré MBSC, alternance de lignes, et
 * mise en forme des montants.
 */
public final class ExcelExporter {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ExcelExporter() {}

    // =========================================================================
    // Historique des transactions
    // =========================================================================
    public static void exporterHistorique(List<LocalTransaction> transactions, File fichier) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Historique des transactions");
            sheet.setDefaultRowHeightInPoints(18);
            setColumnWidths(sheet, 6200, 4500, 5200, 5000, 5500, 5000, 4500);

            // ── Titre & sous-titre ─────────────────────────────────────────
            buildTitleRow(wb, sheet, 0, "MBSC Finapp — Historique des Transactions", 0, 6, "1e8e3e");
            buildSubtitleRow(wb, sheet, 1, "Exporté le : " + LocalDateTime.now().format(DATETIME_FMT), 0, 6);
            buildSubtitleRow(wb, sheet, 2, transactions.size() + " transaction(s)", 0, 6);

            // ── En-têtes ───────────────────────────────────────────────────
            String[] headers = {"Référence", "Type", "Note de frais", "Montant ($)", "Date opération", "N° Reçu", "Sync"};
            buildHeaderRow(wb, sheet, 4, headers, "1e8e3e");

            // ── Données ────────────────────────────────────────────────────
            CellStyle encStyle = coloredRowStyle(wb, "e8f5e9", "1b5e20");
            CellStyle decStyle = coloredRowStyle(wb, "fff3e0", "e65100");
            CellStyle encAmt   = amountStyle(wb, "1b5e20", "e8f5e9");
            CellStyle decAmt   = amountStyle(wb, "e65100", "fff3e0");

            int r = 5;
            for (LocalTransaction tx : transactions) {
                Row row = sheet.createRow(r++);
                boolean enc = tx.getSens() == SensTransaction.ENCAISSEMENT;
                CellStyle base = enc ? encStyle : decStyle;
                CellStyle amt  = enc ? encAmt   : decAmt;

                String dateOp = tx.getDateOperation() != null
                    ? tx.getDateOperation().atZone(ZoneId.systemDefault()).format(DATETIME_FMT) : "";

                setCells(row, base, amt, new Object[]{
                    tx.getReference(),
                    enc ? "Encaissement" : "Décaissement",
                    tx.getNoteReference(),
                    tx.getMontant() != null ? tx.getMontant().doubleValue() : 0.0,
                    dateOp,
                    tx.getNumeroRecu(),
                    tx.isSynced() ? "✓" : "⏳"
                }, 3);
            }

            // ── Totaux ──────────────────────────────────────────────────────
            buildTotalRow(wb, sheet, r + 1, "Total : " + transactions.size() + " transaction(s)", 0, 6, "2e7d32");

            try (FileOutputStream out = new FileOutputStream(fichier)) { wb.write(out); }
        }
    }

    // =========================================================================
    // Grand Livre OHADA
    // =========================================================================
    public static void exporterGrandLivre(List<LocalEcriture> ecritures, File fichier) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Grand Livre OHADA");
            sheet.setDefaultRowHeightInPoints(18);
            setColumnWidths(sheet, 1800, 3800, 3500, 9000, 5000, 5200, 5200);

            buildTitleRow(wb, sheet, 0, "MBSC Finapp — Grand Livre Comptable OHADA", 0, 6, "1a73e8");
            buildSubtitleRow(wb, sheet, 1, "Exporté le : " + LocalDateTime.now().format(DATETIME_FMT), 0, 6);
            buildSubtitleRow(wb, sheet, 2, ecritures.size() + " écriture(s)", 0, 6);

            String[] headers = {"N°", "Date", "Compte", "Libellé", "Caissier", "Débit ($)", "Crédit ($)"};
            buildHeaderRow(wb, sheet, 4, headers, "1a73e8");

            CellStyle evenStyle = dataStyle(wb, "e3f2fd");
            CellStyle oddStyle  = dataStyle(wb, "ffffff");
            CellStyle debitAmt  = amountStyle(wb, "b71c1c", "ffebee");
            CellStyle creditAmt = amountStyle(wb, "1b5e20", "e8f5e9");

            double totalDebit = 0, totalCredit = 0;
            int r = 5;
            for (int i = 0; i < ecritures.size(); i++) {
                LocalEcriture e  = ecritures.get(i);
                Row row = sheet.createRow(r++);
                CellStyle base = (i % 2 == 0) ? evenStyle : oddStyle;

                String caissier = "";
                if (e.getTransaction() != null && e.getTransaction().getCaissierEmail() != null) {
                    String email = e.getTransaction().getCaissierEmail();
                    int at = email.indexOf('@');
                    caissier = at > 0 ? email.substring(0, at) : email;
                }
                double debit  = e.getDebit()  != null ? e.getDebit().doubleValue()  : 0;
                double credit = e.getCredit() != null ? e.getCredit().doubleValue() : 0;
                totalDebit  += debit;
                totalCredit += credit;

                // N°
                Cell cNum = row.createCell(0); cNum.setCellValue(i + 1); cNum.setCellStyle(base);
                // Date
                Cell cDate = row.createCell(1);
                cDate.setCellValue(e.getDateEcriture() != null ? e.getDateEcriture().format(DATE_FMT) : "");
                cDate.setCellStyle(base);
                // Compte
                Cell cCompte = row.createCell(2);
                cCompte.setCellValue(e.getNumeroCompte() != null ? e.getNumeroCompte() : "");
                cCompte.setCellStyle(base);
                // Libellé
                Cell cLib = row.createCell(3);
                cLib.setCellValue(e.getLibelle() != null ? e.getLibelle() : "");
                cLib.setCellStyle(base);
                // Caissier
                Cell cCaiss = row.createCell(4); cCaiss.setCellValue(caissier); cCaiss.setCellStyle(base);
                // Débit
                Cell cDeb = row.createCell(5);
                cDeb.setCellValue(debit > 0 ? debit : 0);
                cDeb.setCellStyle(debit > 0 ? debitAmt : base);
                // Crédit
                Cell cCred = row.createCell(6);
                cCred.setCellValue(credit > 0 ? credit : 0);
                cCred.setCellStyle(credit > 0 ? creditAmt : base);
            }

            // Ligne totaux
            int totRow = r + 1;
            buildTotalRow(wb, sheet, totRow, "TOTAUX", 0, 4, "1a73e8");
            Row tot = sheet.getRow(totRow);
            CellStyle totAmt = amountStyle(wb, "ffffff", "1a73e8");
            Cell tDeb = tot.createCell(5); tDeb.setCellValue(totalDebit);  tDeb.setCellStyle(totAmt);
            Cell tCred= tot.createCell(6); tCred.setCellValue(totalCredit); tCred.setCellStyle(totAmt);

            try (FileOutputStream out = new FileOutputStream(fichier)) { wb.write(out); }
        }
    }

    // =========================================================================
    // Notes à payer
    // =========================================================================
    public static void exporterNotes(List<NoteDtos.NoteAPayer> notes, File fichier) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Notes à payer");
            sheet.setDefaultRowHeightInPoints(18);
            setColumnWidths(sheet, 5500, 3800, 9500, 5000, 4500);

            buildTitleRow(wb, sheet, 0, "MBSC Finapp — Notes de frais à payer", 0, 4, "f57c00");
            buildSubtitleRow(wb, sheet, 1, "Exporté le : " + LocalDateTime.now().format(DATETIME_FMT), 0, 4);
            buildSubtitleRow(wb, sheet, 2, notes.size() + " note(s) en attente", 0, 4);

            String[] headers = {"Référence", "Priorité", "Objet", "Montant ($)", "Lignes"};
            buildHeaderRow(wb, sheet, 4, headers, "f57c00");

            CellStyle evenStyle = dataStyle(wb, "fff8e1");
            CellStyle oddStyle  = dataStyle(wb, "ffffff");
            CellStyle hauteStyle = coloredRowStyle(wb, "ffebee", "b71c1c");

            int r = 5;
            for (int i = 0; i < notes.size(); i++) {
                NoteDtos.NoteAPayer n = notes.get(i);
                Row row = sheet.createRow(r++);
                boolean haute = "HAUTE".equals(n.priorite());
                CellStyle base = haute ? hauteStyle : ((i % 2 == 0) ? evenStyle : oddStyle);

                Cell c0 = row.createCell(0); c0.setCellValue(nvl(n.reference())); c0.setCellStyle(base);
                Cell c1 = row.createCell(1); c1.setCellValue(nvl(n.priorite())); c1.setCellStyle(base);
                Cell c2 = row.createCell(2); c2.setCellValue(nvl(n.objet())); c2.setCellStyle(base);
                Cell c3 = row.createCell(3);
                c3.setCellValue(n.montant() != null ? n.montant().doubleValue() : 0);
                c3.setCellStyle(amountStyle(wb, "e65100", haute ? "ffebee" : (i % 2 == 0 ? "fff8e1" : "ffffff")));
                Cell c4 = row.createCell(4);
                c4.setCellValue(n.nombreLignes() != null ? n.nombreLignes() : 1);
                c4.setCellStyle(base);
            }

            buildTotalRow(wb, sheet, r + 1, "Total : " + notes.size() + " note(s)", 0, 4, "f57c00");
            try (FileOutputStream out = new FileOutputStream(fichier)) { wb.write(out); }
        }
    }

    // =========================================================================
    // Helpers styles
    // =========================================================================

    private static void buildTitleRow(XSSFWorkbook wb, XSSFSheet sheet, int rowNum,
                                       String text, int c1, int c2, String hexColor) {
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, c1, c2));
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(28);
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexColor), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setLeftBorderColor(new XSSFColor(hex(hexColor), null));
        XSSFFont font = wb.createFont();
        font.setBold(true); font.setFontHeightInPoints((short) 15);
        font.setColor(new XSSFColor(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff}, null));
        style.setFont(font);
        style.setLeftBorderColor(new XSSFColor(hex(hexColor), null));
        Cell cell = row.createCell(c1);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static void buildSubtitleRow(XSSFWorkbook wb, XSSFSheet sheet, int rowNum,
                                          String text, int c1, int c2) {
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, c1, c2));
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(16);
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex("f5f5f5"), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = wb.createFont();
        font.setItalic(true); font.setFontHeightInPoints((short) 10);
        font.setColor(new XSSFColor(hex("757575"), null));
        style.setFont(font);
        Cell cell = row.createCell(c1);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static void buildHeaderRow(XSSFWorkbook wb, XSSFSheet sheet, int rowNum,
                                        String[] headers, String hexColor) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(22);
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexColor), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.MEDIUM);
        style.setBottomBorderColor(new XSSFColor(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff}, null));
        XSSFFont font = wb.createFont();
        font.setBold(true); font.setFontHeightInPoints((short) 11);
        font.setColor(new XSSFColor(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff}, null));
        style.setFont(font);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private static void buildTotalRow(XSSFWorkbook wb, XSSFSheet sheet, int rowNum,
                                       String text, int c1, int c2, String hexColor) {
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, c1, c2));
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(20);
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexColor), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = wb.createFont();
        font.setBold(true); font.setFontHeightInPoints((short) 11);
        font.setColor(new XSSFColor(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff}, null));
        style.setFont(font);
        Cell cell = row.createCell(c1);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static CellStyle dataStyle(XSSFWorkbook wb, String hexBg) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexBg), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(new XSSFColor(hex("e0e0e0"), null));
        XSSFFont font = wb.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        return style;
    }

    private static CellStyle coloredRowStyle(XSSFWorkbook wb, String hexBg, String hexText) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexBg), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(new XSSFColor(hex("e0e0e0"), null));
        XSSFFont font = wb.createFont();
        font.setColor(new XSSFColor(hex(hexText), null));
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        return style;
    }

    private static CellStyle amountStyle(XSSFWorkbook wb, String hexText, String hexBg) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(hex(hexBg), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(new XSSFColor(hex("e0e0e0"), null));
        XSSFFont font = wb.createFont();
        font.setBold(true); font.setFontHeightInPoints((short) 10);
        font.setColor(new XSSFColor(hex(hexText), null));
        style.setFont(font);
        return style;
    }

    private static void setCells(Row row, CellStyle base, CellStyle amtStyle,
                                   Object[] values, int amtCol) {
        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            Object v = values[i];
            if (v instanceof Double d) c.setCellValue(d);
            else c.setCellValue(v != null ? v.toString() : "");
            c.setCellStyle(i == amtCol ? amtStyle : base);
        }
    }

    private static void setColumnWidths(XSSFSheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
    }

    private static byte[] hex(String hex) {
        hex = hex.replaceFirst("#", "");
        return new byte[]{
            (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}

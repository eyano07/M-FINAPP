package com.mbsc.desktop.service;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import com.mbsc.desktop.model.LocalEcriture;
import com.mbsc.desktop.model.LocalTransaction;
import com.mbsc.desktop.model.SensTransaction;

/**
 * Génère un PDF moderne pour l'impression de l'historique ou du grand livre.
 * Design : en-tête dégradé, tableau avec alternance de couleurs, pied de page.
 */
public final class PrintService {

    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final NumberFormat      MONTANT_FMT;
    static {
        MONTANT_FMT = NumberFormat.getNumberInstance(Locale.FRENCH);
        MONTANT_FMT.setMinimumFractionDigits(2);
        MONTANT_FMT.setMaximumFractionDigits(2);
        MONTANT_FMT.setGroupingUsed(true);
    }

    // Palette épurée — seulement l'essentiel
    private static final float[] GREEN_DARK  = {0.05f, 0.43f, 0.17f};   // en-tête histo
    private static final float[] BLUE_DARK   = {0.10f, 0.28f, 0.65f};   // en-tête GL
    private static final float[] WHITE       = {1f, 1f, 1f};
    private static final float[] GREY_ROW    = {0.96f, 0.96f, 0.96f};   // ligne paire
    private static final float[] TEXT_DARK   = {0.10f, 0.10f, 0.10f};
    private static final float[] TEXT_GREY   = {0.50f, 0.50f, 0.50f};
    // Alias inutilisés mais conservés pour compatibilité interne
    private static final float[] GREEN_LIGHT  = GREEN_DARK;
    private static final float[] BLUE_LIGHT   = BLUE_DARK;
    private static final float[] GREY_LIGHT   = GREY_ROW;
    private static final float[] ENC_BG       = WHITE;   // plus de couleur par type
    private static final float[] DEC_BG       = WHITE;
    private static final float[] PURPLE_DARK  = {0.37f, 0.21f, 0.69f};

    private static final float PAGE_W  = PDRectangle.A4.getWidth();
    private static final float PAGE_H  = PDRectangle.A4.getHeight();
    private static final float MARGIN  = 40f;
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;
    private static final float ROW_H   = 18f;
    private static final float HDR_H   = 80f;

    private PrintService() {}

    // =========================================================================
    // HISTORIQUE DES TRANSACTIONS
    // =========================================================================

    public static void imprimerHistorique(List<LocalTransaction> txs, File dest,
                                           LocalDate dateFrom, LocalDate dateTo,
                                           String searchText) throws IOException {
        try (PDDocument doc = new PDDocument()) {

            PDType1Font fontReg  = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            String titre  = "Historique des Transactions";
            String filtreLabel = buildFiltreLabel(searchText, dateFrom, dateTo);

            // ─── Calcul totaux ─────────────────────────────────────────
            double totEnc = txs.stream().filter(t -> t.getSens() == SensTransaction.ENCAISSEMENT)
                               .mapToDouble(t -> t.getMontant() != null ? t.getMontant().doubleValue() : 0).sum();
            double totDec = txs.stream().filter(t -> t.getSens() == SensTransaction.DECAISSEMENT)
                               .mapToDouble(t -> t.getMontant() != null ? t.getMontant().doubleValue() : 0).sum();
            double solde  = totEnc - totDec;

            // Colonnes : [Référence, Type, Note, Montant ($), Date, Reçu, Sync]
            float[] cols = {95f, 85f, 90f, 80f, 100f, 85f, 55f};
            String[] headers = {"Référence", "Type", "Note de frais", "Montant ($)", "Date opération", "N° Reçu", "Sync"};

            // ─── Pagination ────────────────────────────────────────────
            int rowsPerPage = (int) ((PAGE_H - HDR_H - 130) / ROW_H);
            int totalPages  = Math.max(1, (int) Math.ceil((double) txs.size() / rowsPerPage));

            for (int p = 0; p < totalPages; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

                    // En-tête
                    drawGradientHeader(cs, GREEN_DARK, GREEN_LIGHT, titre, filtreLabel,
                        "MBSC Finapp — Caisse", fontBold, fontReg);

                    // Résumé (page 1 seulement)
                    float y = PAGE_H - HDR_H - 10;
                    if (p == 0) {
                        y = drawSummaryHisto(cs, fontBold, fontReg, y, totEnc, totDec, solde, txs.size());
                    }

                    // En-têtes tableau
                    y = drawTableHeader(cs, fontBold, y - 5, cols, headers, GREEN_DARK);

                    // Lignes
                    int start = p * rowsPerPage;
                    int end   = Math.min(start + rowsPerPage, txs.size());
                    for (int i = start; i < end; i++) {
                        LocalTransaction tx = txs.get(i);
                        boolean enc = tx.getSens() == SensTransaction.ENCAISSEMENT;
                        float[] bg = enc ? ENC_BG : DEC_BG;
                        String dateStr = tx.getDateOperation() != null
                            ? tx.getDateOperation().atZone(ZoneId.systemDefault()).format(DATETIME_FMT) : "—";
                        String montant = tx.getMontant() != null
                            ? MONTANT_FMT.format(tx.getMontant()) + " $" : "—";
                        String[] vals = {
                            nvl(tx.getReference()),
                            enc ? "Encaissement" : "Décaissement",
                            nvl2(tx.getNoteReference()),
                            montant,
                            dateStr,
                            nvl2(tx.getNumeroRecu()),
                            tx.isSynced() ? "✓" : "⏳"
                        };
                        y = drawRow(cs, fontReg, fontBold, y, cols, vals, bg, i % 2 == 0, 3);
                        if (y < MARGIN + 30) break;
                    }

                    // Pied de page
                    drawFooter(cs, fontReg, p + 1, totalPages);
                }
            }
            doc.save(dest);
        }
        openFile(dest);
    }

    // =========================================================================
    // GRAND LIVRE OHADA
    // =========================================================================

    public static void imprimerGrandLivre(List<LocalEcriture> ecritures, File dest,
                                           LocalDate dateFrom, LocalDate dateTo,
                                           String searchText) throws IOException {
        try (PDDocument doc = new PDDocument()) {

            PDType1Font fontReg  = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            String titre  = "Grand Livre Comptable OHADA";
            String filtreLabel = buildFiltreLabel(searchText, dateFrom, dateTo);

            double totDebit  = ecritures.stream().mapToDouble(e -> e.getDebit()  != null ? e.getDebit().doubleValue()  : 0).sum();
            double totCredit = ecritures.stream().mapToDouble(e -> e.getCredit() != null ? e.getCredit().doubleValue() : 0).sum();

            float[] cols    = {28f, 60f, 48f, 170f, 80f, 80f, 80f};
            String[] headers= {"N°", "Date", "Compte", "Libellé", "Caissier", "Débit ($)", "Crédit ($)"};

            int rowsPerPage = (int) ((PAGE_H - HDR_H - 110) / ROW_H);
            int totalPages  = Math.max(1, (int) Math.ceil((double) ecritures.size() / rowsPerPage));

            for (int p = 0; p < totalPages; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

                    drawGradientHeader(cs, BLUE_DARK, BLUE_LIGHT, titre, filtreLabel,
                        "MBSC Finapp — Comptabilité OHADA", fontBold, fontReg);

                    float y = PAGE_H - HDR_H - 10;
                    if (p == 0) {
                        y = drawSummaryGl(cs, fontBold, fontReg, y, totDebit, totCredit, ecritures.size());
                    }

                    y = drawTableHeader(cs, fontBold, y - 5, cols, headers, BLUE_DARK);

                    int start = p * rowsPerPage;
                    int end   = Math.min(start + rowsPerPage, ecritures.size());
                    for (int i = start; i < end; i++) {
                        LocalEcriture e = ecritures.get(i);
                        float[] bg = (i % 2 == 0) ? GREY_ROW : WHITE;
                        String caissier = "";
                        if (e.getTransaction() != null && e.getTransaction().getCaissierEmail() != null) {
                            String email = e.getTransaction().getCaissierEmail();
                            int at = email.indexOf('@');
                            caissier = at > 0 ? email.substring(0, at) : email;
                        }
                        double deb  = e.getDebit()  != null ? e.getDebit().doubleValue()  : 0;
                        double cred = e.getCredit() != null ? e.getCredit().doubleValue() : 0;
                        String[] vals = {
                            String.valueOf(i + 1),
                            e.getDateEcriture() != null ? e.getDateEcriture().format(DATE_FMT) : "—",
                            nvl(e.getNumeroCompte()),
                            nvl(e.getLibelle()),
                            caissier,
                            deb  > 0 ? MONTANT_FMT.format(deb)  + " $" : "—",
                            cred > 0 ? MONTANT_FMT.format(cred) + " $" : "—"
                        };
                        // Débit/Crédit en gras (col 5 et 6)
                        y = drawRowGl(cs, fontReg, fontBold, y, cols, vals, bg, deb > 0 ? 5 : (cred > 0 ? 6 : -1));
                        if (y < MARGIN + 30) break;
                    }

                    // Ligne totaux
                    if (p == totalPages - 1) {
                        y = drawTotalsRow(cs, fontBold, y - 4, cols,
                            new String[]{"TOTAUX", "", "", "", "",
                                MONTANT_FMT.format(totDebit)  + " $",
                                MONTANT_FMT.format(totCredit) + " $"},
                            BLUE_DARK);
                    }

                    drawFooter(cs, fontReg, p + 1, totalPages);
                }
            }
            doc.save(dest);
        }
        openFile(dest);
    }

    // =========================================================================
    // Dessin helpers
    // =========================================================================

    private static void drawGradientHeader(PDPageContentStream cs,
                                            float[] c1, float[] c2,
                                            String titre, String filtreLbl,
                                            String sousTitre,
                                            PDType1Font bold, PDType1Font reg) throws IOException {
        // Fond plein monochrome (une seule couleur, pas de dégradé)
        cs.setNonStrokingColor(c1[0], c1[1], c1[2]);
        cs.addRect(0, PAGE_H - HDR_H, PAGE_W, HDR_H);
        cs.fill();

        // Titre
        cs.setNonStrokingColor(WHITE[0], WHITE[1], WHITE[2]);
        cs.beginText();
        cs.setFont(bold, 18);
        cs.newLineAtOffset(MARGIN, PAGE_H - 38);
        cs.showText(titre);
        cs.endText();

        // Sous-titre
        cs.beginText();
        cs.setFont(reg, 10);
        cs.newLineAtOffset(MARGIN, PAGE_H - 55);
        cs.showText(sousTitre);
        cs.endText();

        // Filtre label (à droite)
        cs.beginText();
        cs.setFont(reg, 9);
        float filtreW = filtreLbl.length() * 5f;
        cs.newLineAtOffset(PAGE_W - MARGIN - filtreW, PAGE_H - 38);
        cs.showText(filtreLbl);
        cs.endText();

        // Date impression
        cs.beginText();
        cs.setFont(reg, 8);
        String now = "Imprimé le : " + java.time.LocalDateTime.now().format(DATETIME_FMT);
        cs.newLineAtOffset(PAGE_W - MARGIN - 130, PAGE_H - 55);
        cs.showText(now);
        cs.endText();
    }

    private static float drawSummaryHisto(PDPageContentStream cs,
                                           PDType1Font bold, PDType1Font reg,
                                           float y, double totEnc, double totDec,
                                           double solde, int count) throws IOException {
        y -= 8;
        float boxW = (CONTENT_W - 20) / 3f;
        float boxH = 36f;
        float x = MARGIN;

        // [label, valeur, r,g,b bordure]
        Object[][] boxes = {
            {"Encaissements", MONTANT_FMT.format(totEnc) + " $", 0.05f, 0.55f, 0.20f},
            {"Décaissements", MONTANT_FMT.format(totDec) + " $", 0.80f, 0.22f, 0.10f},
            {"Solde net",     (solde >= 0 ? "+" : "") + MONTANT_FMT.format(solde) + " $",
             solde >= 0 ? 0.05f : 0.80f, solde >= 0 ? 0.43f : 0.22f, solde >= 0 ? 0.17f : 0.10f}
        };

        for (Object[] box : boxes) {
            float r = (Float) box[2], g = (Float) box[3], b = (Float) box[4];
            // fond blanc avec cadre léger gris
            cs.setNonStrokingColor(0.97f, 0.97f, 0.97f);
            cs.addRect(x, y - boxH, boxW - 4, boxH); cs.fill();
            // bordure gauche colorée (4px)
            cs.setNonStrokingColor(r, g, b);
            cs.addRect(x, y - boxH, 4, boxH); cs.fill();
            // label gris
            cs.setNonStrokingColor(TEXT_GREY[0], TEXT_GREY[1], TEXT_GREY[2]);
            cs.beginText(); cs.setFont(reg, 8);
            cs.newLineAtOffset(x + 10, y - 14); cs.showText((String) box[0]); cs.endText();
            // valeur noire
            cs.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2]);
            cs.beginText(); cs.setFont(bold, 11);
            cs.newLineAtOffset(x + 10, y - 28); cs.showText((String) box[1]); cs.endText();
            x += boxW + 10;
        }

        cs.setNonStrokingColor(TEXT_GREY[0], TEXT_GREY[1], TEXT_GREY[2]);
        cs.beginText(); cs.setFont(reg, 8);
        cs.newLineAtOffset(MARGIN, y - boxH - 8);
        cs.showText(count + " transaction(s) affichée(s)"); cs.endText();

        return y - boxH - 20;
    }

    private static float drawSummaryGl(PDPageContentStream cs,
                                        PDType1Font bold, PDType1Font reg,
                                        float y, double totDeb, double totCred, int count) throws IOException {
        y -= 8;
        float boxW = (CONTENT_W - 10) / 2f;
        float boxH = 36f;

        Object[][] boxes = {
            {"Total Débits",  MONTANT_FMT.format(totDeb)  + " $", 0.72f, 0.11f, 0.11f},
            {"Total Crédits", MONTANT_FMT.format(totCred) + " $", 0.05f, 0.43f, 0.17f}
        };
        float x = MARGIN;
        for (Object[] box : boxes) {
            float r = (Float) box[2], g = (Float) box[3], b = (Float) box[4];
            cs.setNonStrokingColor(0.97f, 0.97f, 0.97f);
            cs.addRect(x, y - boxH, boxW - 4, boxH); cs.fill();
            cs.setNonStrokingColor(r, g, b);
            cs.addRect(x, y - boxH, 4, boxH); cs.fill();
            cs.setNonStrokingColor(TEXT_GREY[0], TEXT_GREY[1], TEXT_GREY[2]);
            cs.beginText(); cs.setFont(reg, 8);
            cs.newLineAtOffset(x + 10, y - 14); cs.showText((String) box[0]); cs.endText();
            cs.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2]);
            cs.beginText(); cs.setFont(bold, 11);
            cs.newLineAtOffset(x + 10, y - 28); cs.showText((String) box[1]); cs.endText();
            x += boxW + 10;
        }

        cs.setNonStrokingColor(TEXT_GREY[0], TEXT_GREY[1], TEXT_GREY[2]);
        cs.beginText(); cs.setFont(reg, 8);
        cs.newLineAtOffset(MARGIN, y - boxH - 8);
        cs.showText(count + " écriture(s) affichée(s)"); cs.endText();

        return y - boxH - 20;
    }

    private static float drawTableHeader(PDPageContentStream cs, PDType1Font bold,
                                          float y, float[] cols,
                                          String[] headers, float[] color) throws IOException {
        float x = MARGIN;
        // Fond en-tête
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        float totalW = 0; for (float c : cols) totalW += c;
        cs.addRect(MARGIN, y - ROW_H, totalW, ROW_H);
        cs.fill();

        cs.setNonStrokingColor(WHITE[0], WHITE[1], WHITE[2]);
        cs.beginText(); cs.setFont(bold, 8);
        for (int i = 0; i < headers.length; i++) {
            cs.newLineAtOffset(i == 0 ? x + 4 : cols[i - 1], 0);
            if (i == 0) cs.newLineAtOffset(0, y - ROW_H + 5);
            cs.showText(truncate(headers[i], cols[i], 7));
            x += cols[i];
        }
        cs.endText();
        return y - ROW_H;
    }

    private static float drawRow(PDPageContentStream cs, PDType1Font reg, PDType1Font bold,
                                   float y, float[] cols, String[] vals,
                                   float[] bg, boolean evenRow, int amtCol) throws IOException {
        float totalW = 0; for (float c : cols) totalW += c;
        cs.setNonStrokingColor(bg[0], bg[1], bg[2]);
        cs.addRect(MARGIN, y - ROW_H, totalW, ROW_H);
        cs.fill();

        // Séparateur bas
        cs.setStrokingColor(0.88f, 0.88f, 0.88f);
        cs.setLineWidth(0.3f);
        cs.moveTo(MARGIN, y - ROW_H); cs.lineTo(MARGIN + totalW, y - ROW_H); cs.stroke();

        cs.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2]);
        float x = MARGIN + 4;
        for (int i = 0; i < vals.length; i++) {
            cs.beginText();
            cs.setFont(i == amtCol ? bold : reg, 8);
            cs.newLineAtOffset(x, y - ROW_H + 5);
            cs.showText(truncate(vals[i] != null ? vals[i] : "", cols[i] - 6, 7));
            cs.endText();
            x += cols[i];
        }
        return y - ROW_H;
    }

    private static float drawRowGl(PDPageContentStream cs, PDType1Font reg, PDType1Font bold,
                                    float y, float[] cols, String[] vals,
                                    float[] bg, int boldCol) throws IOException {
        return drawRow(cs, reg, bold, y, cols, vals, bg, false, boldCol);
    }

    private static float drawTotalsRow(PDPageContentStream cs, PDType1Font bold,
                                        float y, float[] cols, String[] vals,
                                        float[] color) throws IOException {
        float totalW = 0; for (float c : cols) totalW += c;
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.addRect(MARGIN, y - ROW_H, totalW, ROW_H); cs.fill();

        cs.setNonStrokingColor(WHITE[0], WHITE[1], WHITE[2]);
        float x = MARGIN + 4;
        for (int i = 0; i < vals.length; i++) {
            cs.beginText(); cs.setFont(bold, 8);
            cs.newLineAtOffset(x, y - ROW_H + 5);
            cs.showText(truncate(vals[i] != null ? vals[i] : "", cols[i] - 6, 7));
            cs.endText();
            x += cols[i];
        }
        return y - ROW_H;
    }

    private static void drawFooter(PDPageContentStream cs, PDType1Font reg,
                                    int page, int total) throws IOException {
        cs.setNonStrokingColor(0.90f, 0.90f, 0.90f);
        cs.addRect(0, MARGIN - 10, PAGE_W, 1); cs.fill();

        cs.setNonStrokingColor(TEXT_GREY[0], TEXT_GREY[1], TEXT_GREY[2]);
        cs.beginText(); cs.setFont(reg, 7);
        cs.newLineAtOffset(MARGIN, MARGIN - 20);
        cs.showText("MBSC Finapp — Document confidentiel — " + page + " / " + total);
        cs.endText();

        cs.beginText(); cs.setFont(reg, 7);
        cs.newLineAtOffset(PAGE_W - MARGIN - 80, MARGIN - 20);
        cs.showText("Généré automatiquement"); cs.endText();
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    private static String buildFiltreLabel(String search, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        if (search != null && !search.isBlank()) sb.append("Filtre: \"").append(search).append("\"  ");
        if (from != null) sb.append("Du ").append(from.format(DATE_FMT)).append(" ");
        if (to   != null) sb.append("Au ").append(to.format(DATE_FMT));
        return sb.length() > 0 ? sb.toString() : "Toutes les données";
    }

    /** Ouvre le PDF avec l'application par défaut (xdg-open sur Linux). */
    private static void openFile(File f) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) {
                new ProcessBuilder("xdg-open", f.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ignored) {}
    }

    /** Tronque le texte pour qu'il ne dépasse pas la largeur de colonne. */
    private static String truncate(String s, float colW, float fontSize) {
        if (s == null) return "";
        int maxChars = (int) (colW / (fontSize * 0.52f));
        return s.length() > maxChars ? s.substring(0, Math.max(0, maxChars - 1)) + "…" : s;
    }

    private static String nvl(String s)  { return s != null ? s : ""; }
    private static String nvl2(String s) { return s != null && !s.isBlank() ? s : "—"; }
}

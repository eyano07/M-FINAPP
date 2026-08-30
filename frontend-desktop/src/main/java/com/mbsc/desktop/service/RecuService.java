package com.mbsc.desktop.service;

import com.mbsc.desktop.model.LocalTransaction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Genere un recu de paiement au format PDF (PDFBox).
 */
public class RecuService {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    public File genererRecu(LocalTransaction tx, File destination) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float x = 70;
                float y = 760;

                cs.beginText();
                cs.setFont(bold, 18);
                cs.newLineAtOffset(x, y);
                cs.showText("MBSC - Recu de paiement");
                cs.endText();

                y -= 10;
                cs.moveTo(x, y);
                cs.lineTo(525, y);
                cs.stroke();

                y -= 40;
                line(cs, bold, normal, x, y, "Numero de recu :", tx.getNumeroRecu());
                y -= 24;
                line(cs, bold, normal, x, y, "Reference transaction :", tx.getReference());
                y -= 24;
                line(cs, bold, normal, x, y, "Note de frais :",
                    tx.getNoteReference() == null ? "-" : tx.getNoteReference());
                y -= 24;
                line(cs, bold, normal, x, y, "Date :", DATE_FMT.format(tx.getDateOperation()));
                y -= 24;
                line(cs, bold, normal, x, y, "Caissier :", tx.getCaissierEmail());
                y -= 24;
                line(cs, bold, normal, x, y, "Sens :", tx.getSens().name());

                y -= 50;
                cs.beginText();
                cs.setFont(bold, 16);
                cs.newLineAtOffset(x, y);
                cs.showText("Montant : " + formatMontant(tx));
                cs.endText();

                y -= 80;
                cs.beginText();
                cs.setFont(normal, 10);
                cs.newLineAtOffset(x, y);
                cs.showText("Document genere automatiquement par MBSC Finapp Desktop.");
                cs.endText();
            }

            doc.save(destination);
            return destination;
        } catch (Exception e) {
            throw new RuntimeException("Echec de generation du recu PDF", e);
        }
    }

    private void line(PDPageContentStream cs, PDType1Font bold, PDType1Font normal,
                      float x, float y, String label, String value) throws Exception {
        cs.beginText();
        cs.setFont(bold, 11);
        cs.newLineAtOffset(x, y);
        cs.showText(label + " ");
        cs.setFont(normal, 11);
        cs.showText(value);
        cs.endText();
    }

    private String formatMontant(LocalTransaction tx) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRANCE);
        return nf.format(tx.getMontant()) + " FCFA";
    }
}

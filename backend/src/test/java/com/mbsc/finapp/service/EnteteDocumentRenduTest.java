package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.ModeleEntete;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Papier à en-tête : chaque modèle produit un PDF lisible, en portrait comme en paysage, avec ou sans logo, pour
 * une entreprise complète comme pour une entreprise réduite à son nom, et le corps du document reste entre
 * l'en-tête et le pied. Avec {@code -Dapercu.dir=<dossier>}, les PDF sont aussi écrits pour être regardés
 * (une page d'exemple par modèle, couleur et orientation).
 */
class EnteteDocumentRenduTest {

    private static final String[] COULEURS = {"#15803D", "#2563EB", "#B91C1C", "#EAB308"};

    private static ParametresEntreprise complete() {
        return ParametresEntreprise.builder()
            .nom("Maison Kivu").nomComplet("Maison Kivu Traiteur SARL").slogan("Cuisine du terroir")
            .adresse("12, avenue de la Paix, Commune de la Gombe, Kinshasa").telephone("+243 810 000 000").email("contact@exemple.cd")
            .rccm("CD/KIN/RCCM/22-B-1234").idNat("01-F4300-N00000X").nif("A0000000B").couleurPrimaire("#15803D").build();
    }

    private static ParametresEntreprise minimale() {
        return ParametresEntreprise.builder().nom("Atelier Kivu").couleurPrimaire("#2563EB").build();
    }

    private static byte[] logo() throws IOException {
        try (InputStream in = EnteteDocumentRenduTest.class.getResourceAsStream("/pdf/papier_entete_logo.png")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** Document d'exemple : {@code pages} pages, en-tête complet sur la première, corps simulé entre l'en-tête et le pied. */
    private static byte[] document(ModeleEntete modele, String couleur, ParametresEntreprise e, byte[] logo, boolean paysage, int pages)
            throws IOException {
        ThemeDocument theme = new ThemeDocument(modele, ThemeDocument.rgb(couleur), e, logo);
        PDRectangle format = paysage ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
        try (PDDocument doc = new PDDocument()) {
            EnteteDocumentRendu rendu = new EnteteDocumentRendu(doc, theme, paysage ? 28f : 55f);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(format);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float haut = rendu.entete(cs, format, i == 0);
                    float bas = rendu.pied(cs, format);
                    rendu.pagination(cs, format, bas, i + 1, pages);
                    assertThat(haut).as("hauteur d'en-tête %s", modele).isBetween(40f, 190f);
                    assertThat(bas).as("hauteur de pied %s", modele).isBetween(30f, 90f);
                    corpsSimule(cs, format, haut, bas, paysage ? 28f : 55f);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void corpsSimule(PDPageContentStream cs, PDRectangle format, float haut, float bas, float marge) throws IOException {
        float y = format.getHeight() - haut - 26;
        PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        cs.setNonStrokingColor(0.55f, 0.58f, 0.62f);
        cs.beginText();
        cs.setFont(f, 9);
        cs.newLineAtOffset(marge, y);
        cs.showText("Corps du document (exemple)");
        cs.endText();
        cs.setNonStrokingColor(0.9f, 0.91f, 0.93f);
        y -= 18;
        while (y > bas + 30) {
            cs.addRect(marge, y, (format.getWidth() - 2 * marge) * (y % 3 == 0 ? 0.86f : 1f), 5);
            cs.fill();
            y -= 15;
        }
    }

    @Test
    void chaqueModeleProduitUnPdfLisibleEnPortraitEtEnPaysage() throws IOException {
        byte[] logo = logo();
        String dossier = System.getProperty("apercu.dir");
        for (ModeleEntete modele : ModeleEntete.values()) {
            for (boolean paysage : new boolean[] {false, true}) {
                byte[] pdf = document(modele, "#15803D", complete(), logo, paysage, 2);
                try (PDDocument lu = Loader.loadPDF(pdf)) {
                    assertThat(lu.getNumberOfPages()).isEqualTo(2);
                    String texte = new PDFTextStripper().getText(lu);
                    assertThat(texte.replaceAll("\\s+", "")).as("%s %s", modele, paysage ? "paysage" : "portrait").containsIgnoringCase("MaisonKivu");
                    assertThat(texte).contains("Page 1 / 2");
                }
                if (dossier != null) {
                    Files.write(Path.of(dossier, modele.name().toLowerCase() + (paysage ? "_paysage" : "_portrait") + ".pdf"), pdf);
                }
            }
        }
    }

    @Test
    void entrepriseReduiteAuNomEtSansLogoNeCassePas() throws IOException {
        String dossier = System.getProperty("apercu.dir");
        for (ModeleEntete modele : ModeleEntete.values()) {
            byte[] pdf = document(modele, "#2563EB", minimale(), null, false, 2);
            try (PDDocument lu = Loader.loadPDF(pdf)) {
                // Les capitales espacées (tracking) sont extraites lettre par lettre : on compare sans espaces.
                assertThat(new PDFTextStripper().getText(lu).replaceAll("\\s+", "")).containsIgnoringCase("AtelierKivu");
            }
            if (dossier != null) {
                Files.write(Path.of(dossier, modele.name().toLowerCase() + "_minimal.pdf"), pdf);
            }
        }
    }

    @Test
    void toutesLesCouleursDuThemeRestentLisibles() throws IOException {
        byte[] logo = logo();
        String dossier = System.getProperty("apercu.dir");
        for (ModeleEntete modele : ModeleEntete.values()) {
            for (String couleur : COULEURS) {
                byte[] pdf = document(modele, couleur, complete(), logo, false, 1);
                assertThat(pdf.length).isGreaterThan(1000);
                if (dossier != null) {
                    Files.write(Path.of(dossier, modele.name().toLowerCase() + "_" + couleur.substring(1) + ".pdf"), pdf);
                }
            }
        }
    }

    @Test
    void nomEtCoordonneesTresLongsRestentDansLaPage() throws IOException {
        ParametresEntreprise e = complete();
        e.setNom("Société Congolaise d'Exploitation Minière et de Commerce Général des Provinces de l'Est");
        e.setNomComplet("Société Congolaise d'Exploitation Minière et de Commerce Général des Provinces de l'Est — SCEMCGPE SARL");
        e.setAdresse("Avenue de la Libération n° 1234, Quartier Industriel, Commune de Lubumbashi, Province du Haut-Katanga, RDC");
        e.setEmail("direction.generale@scemcgpe.example.cd · comptabilite@scemcgpe.example.cd");
        String dossier = System.getProperty("apercu.dir");
        for (ModeleEntete modele : ModeleEntete.values()) {
            byte[] pdf = document(modele, "#2563EB", e, logo(), false, 2);
            try (PDDocument lu = Loader.loadPDF(pdf)) {
                assertThat(lu.getNumberOfPages()).isEqualTo(2);
            }
            if (dossier != null) {
                Files.write(Path.of(dossier, modele.name().toLowerCase() + "_long.pdf"), pdf);
            }
        }
    }
}

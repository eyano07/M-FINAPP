package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.AgentOrdreMission;
import com.mbsc.finapp.domain.OrdreMission;
import com.mbsc.finapp.domain.ParametresPaie;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Génération de l'ordre de mission (individuel ou collectif) sur le papier
 * entête officiel MBSC ({@code /pdf/entete_mbsc.pdf}), utilisé comme fond
 * de page — le contenu est simplement ajouté par-dessus (append mode).
 *
 * <p>Reproduit fidèlement la mise en page du {@code MissionOrderPdfService}
 * de PayMBSC (source), mais avec Apache PDFBox (Apache-2.0) au lieu d'iText 8
 * (AGPL/commercial) : l'API de mise en page manuelle est plus bas niveau,
 * d'où le petit moteur de rendu ({@link Writer}) ci-dessous, mais le rendu
 * visuel — titre, intro, but, bloc méta, clôture, signature — est identique.
 * Aucun sceau n'est apposé : seule une zone de signature manuscrite est
 * prévue, comme dans l'original.</p>
 */
@Service
@RequiredArgsConstructor
public class OrdreMissionPdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRENCH);
    private static final float MARGIN_LEFT = 55;
    private static final float MARGIN_RIGHT = 55;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
    private static final float START_Y = PDRectangle.A4.getHeight() - 125;
    private static final float DARK_R = 10f / 255, DARK_G = 74f / 255, DARK_B = 66f / 255;

    private final ParametresPaieService parametresPaieService;

    public byte[] genererPdf(OrdreMission order) {
        try (InputStream is = getClass().getResourceAsStream("/pdf/entete_mbsc.pdf")) {
            if (is == null) {
                throw new IllegalStateException("Papier entête introuvable (/pdf/entete_mbsc.pdf).");
            }
            byte[] letterhead = is.readAllBytes();
            try (PDDocument doc = Loader.loadPDF(letterhead)) {
                ParametresPaie params = parametresPaieService.get();
                PDPage page = doc.getPage(0);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    Writer w = new Writer(cs, START_Y);
                    buildTitle(w, order);
                    buildIntro(w, order);
                    buildButSection(w, order);
                    buildMetaBlock(w, order);
                    buildClosing(w, order);
                    buildSignature(w, params);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                doc.save(bos);
                return bos.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Échec de la génération du PDF de l'ordre de mission.", e);
        }
    }

    private void buildTitle(Writer w, OrdreMission order) {
        String titre = order.isCollective() ? "ORDRE DE MISSION COLLECTIVE" : "ORDRE DE MISSION";
        w.centered(titre + " N°" + safe(order.getNumero()), Writer.BOLD, 13, DARK_R, DARK_G, DARK_B);
        w.gap(16);
    }

    private void buildIntro(Writer w, OrdreMission order) {
        String lieuPhrase = lieuPhrase(order);
        if (order.isCollective()) {
            w.wrapped("Les personnes dont les noms, post-noms, et fonctions sont repris ci-bas sont "
                + "envoyées en mission de service " + lieuPhrase + ".", Writer.REGULAR, 10.5f);
            w.gap(2);
            w.text("Il s'agit de :", Writer.BOLD, 10.5f, MARGIN_LEFT, 0, 0, 0);
            w.gap(4);
            for (AgentOrdreMission a : order.getAgents()) {
                String nom = a.getEmploye() != null ? a.getEmploye().getNomComplet() : "-";
                String fonction = a.getFonctionMission() != null && !a.getFonctionMission().isBlank()
                    ? a.getFonctionMission() : "-";
                w.bulletItem(safe(a.getCivilite()) + " " + nom, " : " + fonction, 10.5f);
            }
            w.gap(6);
        } else {
            AgentOrdreMission a = order.getAgents().isEmpty() ? null : order.getAgents().get(0);
            String civilite = a != null ? safe(a.getCivilite()) : "Monsieur/Madame";
            String nom = a != null && a.getEmploye() != null ? a.getEmploye().getNomComplet() : "-";
            String fonction = a != null && a.getFonctionMission() != null && !a.getFonctionMission().isBlank()
                ? a.getFonctionMission() : null;
            String verbe = "Madame".equalsIgnoreCase(civilite) ? "est envoyée" : "est envoyé";

            StringBuilder phrase = new StringBuilder(civilite).append(' ').append(nom);
            if (fonction != null) phrase.append(", ").append(fonction).append(',');
            phrase.append(' ').append(verbe).append(" en mission de service ").append(lieuPhrase).append('.');
            w.wrapped(phrase.toString(), Writer.REGULAR, 10.5f);
            w.gap(6);
        }
    }

    private String lieuPhrase(OrdreMission order) {
        StringBuilder sb = new StringBuilder("à ").append(safe(order.getLieuMission()));
        if (order.getProvince() != null && !order.getProvince().isBlank()) {
            sb.append(", dans la Province de ").append(order.getProvince());
        }
        if (order.getDistanceVille() != null) {
            sb.append(" (à environ ").append(formatDistance(order.getDistanceVille().doubleValue())).append(" km de la ville)");
        }
        return sb.toString();
    }

    private String formatDistance(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.format(Locale.FRENCH, "%.1f", d);
    }

    private void buildButSection(Writer w, OrdreMission order) {
        w.text("BUT DE LA MISSION", Writer.BOLD, 10.5f, MARGIN_LEFT, DARK_R, DARK_G, DARK_B);
        w.gap(2);
        w.wrapped(safe(order.getButMission()), Writer.REGULAR, 10.5f);
        w.gap(10);
    }

    private void buildMetaBlock(Writer w, OrdreMission order) {
        addMetaRow(w, "DURÉE DE LA MISSION", safe(order.getDureeMission()));
        addMetaRow(w, "DATE DE DÉPART", order.getDateDepart() != null ? order.getDateDepart().format(DATE_FMT) : "-");
        addMetaRow(w, "DATE DE RETOUR", order.getDateRetour() != null ? order.getDateRetour().format(DATE_FMT) : "-");
        addMetaRow(w, "MOYEN DE TRANSPORT", safe(order.getMoyenTransport()));
        addMetaRow(w, "FRAIS DE MISSION", safe(order.getFraisMission()));
        w.gap(10);
    }

    private void addMetaRow(Writer w, String label, String value) {
        String v = (value == null || value.isBlank()) ? "-" : value;
        w.metaRow(label, ": " + v);
    }

    private void buildClosing(Writer w, OrdreMission order) {
        String pronoms = order.isCollective()
            ? "aux personnes ci-haut désignées pour l'accomplissement de leur mission"
            : "à la personne ci-haut désignée pour l'accomplissement de sa mission";
        w.wrapped("Les autorités tant administratives, militaires que policières sont priées "
            + "de porter assistance " + pronoms + ".", Writer.REGULAR, 10.5f);
        w.gap(20);
    }

    private void buildSignature(Writer w, ParametresPaie s) {
        String ville = s.getVilleSignature() != null && !s.getVilleSignature().isBlank()
            ? s.getVilleSignature() : "Lubumbashi";
        String date = LocalDate.now().format(DATE_FMT);
        w.rightAligned("Fait à " + ville + ", le " + date + ".", Writer.OBLIQUE, 10);
        w.gap(6);

        String nom = s.getDirecteurDrh() != null ? s.getDirecteurDrh().trim() : "";
        String fonction = s.getFonctionDirecteur() != null && !s.getFonctionDirecteur().isBlank()
            ? s.getFonctionDirecteur() : "Directeur des Ressources Humaines";

        if (!nom.isBlank()) {
            w.rightAligned(nom.toUpperCase(Locale.FRENCH), Writer.BOLD, 11, DARK_R, DARK_G, DARK_B);
        }
        w.rightAligned(fonction + ".", Writer.REGULAR, 10);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Petit moteur de rendu texte au-dessus du contenu existant d'une page
     * PDFBox (coordonnées bas-gauche, y croissant vers le haut) : habillage
     * de texte, alignement centré/droit, puces et lignes clé-valeur — tout
     * ce dont {@code buildXxx} a besoin, rien de plus (pas de moteur de mise
     * en page générique).
     */
    private static final class Writer {
        static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        static final PDFont OBLIQUE = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        private static final float LEADING = 13.5f;

        private final PDPageContentStream cs;
        private float y;

        Writer(PDPageContentStream cs, float startY) {
            this.cs = cs;
            this.y = startY;
        }

        void gap(float amount) {
            y -= amount;
        }

        void text(String s, PDFont font, float size, float x, float r, float g, float b) {
            draw(s, font, size, x, r, g, b);
            y -= LEADING;
        }

        void centered(String s, PDFont font, float size, float r, float g, float b) {
            float width = widthOf(s, font, size);
            draw(s, font, size, (PAGE_WIDTH - width) / 2, r, g, b);
            y -= LEADING;
        }

        void rightAligned(String s, PDFont font, float size) {
            rightAligned(s, font, size, 0, 0, 0);
        }

        void rightAligned(String s, PDFont font, float size, float r, float g, float b) {
            float width = widthOf(s, font, size);
            draw(s, font, size, PAGE_WIDTH - MARGIN_RIGHT - width, r, g, b);
            y -= LEADING;
        }

        void bulletItem(String bold, String rest, float size) {
            float x = MARGIN_LEFT + 18;
            draw("•  " + bold + rest, REGULAR, size, x, 0, 0, 0);
            y -= LEADING - 3.5f;
        }

        void metaRow(String label, String value) {
            draw(label, BOLD, 10, MARGIN_LEFT, 0, 0, 0);
            draw(value, REGULAR, 10, MARGIN_LEFT + 145, 0, 0, 0);
            y -= LEADING - 1.5f;
        }

        /** Habille {@code text} sur {@link #USABLE_WIDTH} et dessine chaque ligne. */
        void wrapped(String text, PDFont font, float size) {
            for (String line : wrap(text, font, size, USABLE_WIDTH)) {
                draw(line, font, size, MARGIN_LEFT, 0, 0, 0);
                y -= LEADING;
            }
        }

        private List<String> wrap(String text, PDFont font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (widthOf(candidate, font, size) > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines;
        }

        private float widthOf(String s, PDFont font, float size) {
            try {
                return font.getStringWidth(s) / 1000 * size;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void draw(String s, PDFont font, float size, float x, float r, float g, float b) {
            try {
                cs.beginText();
                cs.setFont(font, size);
                cs.setNonStrokingColor(r, g, b);
                cs.newLineAtOffset(x, y);
                cs.showText(s);
                cs.endText();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

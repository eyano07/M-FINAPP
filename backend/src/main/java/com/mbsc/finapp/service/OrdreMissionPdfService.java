package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.AgentOrdreMission;
import com.mbsc.finapp.domain.OrdreMission;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Génération de l'ordre de mission (individuel ou collectif) sur le papier à
 * en-tête de l'entreprise, dessiné selon le modèle et la couleur choisis par
 * l'administrateur (voir {@link EnteteDocumentRendu}) — le même habillage que
 * les autres PDF de l'application.
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
    // Position (repere bas-gauche PDFBox) ou demarre le bloc signature s'il
    // reste de la place : l'empeche de suivre immediatement le paragraphe de
    // cloture (qui laissait un grand vide en dessous sur une mission courte)
    // tout en gardant une marge confortable au-dessus du pied de page.
    private static final float SIGNATURE_ZONE_Y = 115f;

    private final ParametresPaieService parametresPaieService;
    private final ThemeDocumentService themeService;

    public byte[] genererPdf(OrdreMission order) {
        try (PDDocument doc = new PDDocument()) {
            ParametresPaie params = parametresPaieService.get();
            ThemeDocument theme = themeService.theme();
            PDRectangle format = PDRectangle.A4;
            PDPage page = new PDPage(format);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                EnteteDocumentRendu rendu = new EnteteDocumentRendu(doc, theme, MARGIN_LEFT);
                float hauteurEntete = rendu.entete(cs, format, true);
                rendu.pied(cs, format);
                // Le corps commence sous l'en-tête, au même endroit qu'avec l'ancien papier statique (125 pt)
                // quand l'en-tête est plus court.
                Writer w = new Writer(cs, format.getHeight() - Math.max(125f, hauteurEntete + 28f), theme.fonce());
                buildTitle(w, order);
                buildIntro(w, order, theme.entreprise());
                buildButSection(w, order);
                buildMetaBlock(w, order);
                buildClosing(w, order);
                w.pousserVers(SIGNATURE_ZONE_Y);
                buildSignature(w, params);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Échec de la génération du PDF de l'ordre de mission.", e);
        }
    }

    private void buildTitle(Writer w, OrdreMission order) {
        String titre = order.isCollective() ? "ORDRE DE MISSION COLLECTIVE" : "ORDRE DE MISSION";
        w.centered(titre + " N°" + safe(order.getNumero()), Writer.BOLD, 13, w.accent[0], w.accent[1], w.accent[2]);
        w.gap(20);
    }

    private void buildIntro(Writer w, OrdreMission order, ParametresEntreprise entreprise) {
        String lieuPhrase = lieuPhrase(order);
        if (order.isCollective()) {
            String superviseurNom = order.getSuperviseur() != null ? order.getSuperviseur().getNomComplet() : "-";
            String nomSociete = StringUtils.hasText(entreprise.getNom()) ? entreprise.getNom() : "la société";
            StringBuilder supervision = new StringBuilder("Sous la supervision de ")
                .append(safe(order.getSuperviseurCivilite())).append(' ').append(superviseurNom)
                .append(", agent de ").append(nomSociete)
                .append(", les personnes ci-après, dont les noms, post-noms et prénoms, nationalités "
                + "et fonctions suivantes sont désignées pour effectuer une mission de service ")
                .append(lieuPhrase).append(';');
            w.wrapped(supervision.toString(), Writer.REGULAR, 10.5f);
            w.gap(8);
            w.text("Il s'agit de :", Writer.BOLD, 10.5f, MARGIN_LEFT, 0, 0, 0);
            w.gap(6);
            int n = 1;
            for (AgentOrdreMission a : order.getAgents()) {
                String identite = safe(a.getCivilite()) + " " + a.nomAffiche() + identiteSuffixe(a);
                String fonction = a.getFonctionMission() != null && !a.getFonctionMission().isBlank()
                    ? a.getFonctionMission() : "-";
                w.numberedItem(n++, identite, " : " + fonction, 10.5f);
            }
            w.gap(16);
        } else {
            // Pas de fonction ici (contrairement a la liste numerotee des missions collectives) : une
            // mission individuelle ne cite que l'agent, sans qualificatif — demande explicite, la fonction
            // saisie dans le formulaire ne doit pas apparaitre dans cette phrase.
            AgentOrdreMission a = order.getAgents().isEmpty() ? null : order.getAgents().get(0);
            String civilite = a != null ? safe(a.getCivilite()) : "Monsieur/Madame";
            String nom = a != null ? a.nomAffiche() : "-";
            String identiteSuffixe = a != null ? identiteSuffixe(a) : "";
            String verbe = "Madame".equalsIgnoreCase(civilite) ? "est envoyée" : "est envoyé";

            StringBuilder phrase = new StringBuilder(civilite).append(' ').append(nom).append(identiteSuffixe)
                .append(' ').append(verbe).append(" en mission de service ").append(lieuPhrase).append('.');
            w.wrapped(phrase.toString(), Writer.REGULAR, 10.5f);
            w.gap(16);
        }
    }

    /** ", nationalité X, Passeport n°Y" — vide si aucune des deux n'est renseignée. */
    private String identiteSuffixe(AgentOrdreMission a) {
        StringBuilder sb = new StringBuilder();
        if (a.getNationalite() != null && !a.getNationalite().isBlank()) {
            sb.append(", nationalité ").append(a.getNationalite());
        }
        if (a.getNumeroPasseport() != null && !a.getNumeroPasseport().isBlank()) {
            sb.append(", Passeport n°").append(a.getNumeroPasseport());
        }
        return sb.toString();
    }

    private String lieuPhrase(OrdreMission order) {
        StringBuilder sb = new StringBuilder("sur le site ").append(safe(order.getLieuMission()));
        if (order.getTerritoire() != null && !order.getTerritoire().isBlank()) {
            sb.append(" sis dans le territoire de ").append(order.getTerritoire());
        }
        if (order.getProvince() != null && !order.getProvince().isBlank()) {
            sb.append(" situé dans la province ").append(order.getProvince());
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
        w.text("BUT DE LA MISSION", Writer.BOLD, 10.5f, MARGIN_LEFT, w.accent[0], w.accent[1], w.accent[2]);
        w.gap(4);
        // Une ligne saisie = un point : chaque retour a la ligne devient sa propre puce, comme sur le modele.
        for (String ligne : safe(order.getButMission()).split("\\r?\\n")) {
            if (!ligne.isBlank()) {
                w.bulletItem("", ligne.trim(), 10.5f);
            }
        }
        w.gap(18);
    }

    private void buildMetaBlock(Writer w, OrdreMission order) {
        addMetaRow(w, "LIEU DE LA MISSION", safe(order.getLieuMission()));
        addMetaRow(w, "DURÉE DE LA MISSION", safe(order.getDureeMission()));
        addMetaRow(w, "DATE DE DÉPART", order.getDateDepart() != null ? order.getDateDepart().format(DATE_FMT) : "-");
        addMetaRow(w, "DATE DE RETOUR", order.getDateRetour() != null ? order.getDateRetour().format(DATE_FMT) : "-");
        addMetaRow(w, "MOYEN DE TRANSPORT", safe(order.getMoyenTransport()));
        addMetaRow(w, "FRAIS DE MISSION", safe(order.getFraisMission()));
        w.gap(20);
    }

    private void addMetaRow(Writer w, String label, String value) {
        String v = (value == null || value.isBlank()) ? "-" : value;
        w.metaRow(label, ": " + v);
    }

    private void buildClosing(Writer w, OrdreMission order) {
        w.wrapped("Les autorités tant administratives, militaires que policières sont priées de considérer "
            + "la présente et leur porter assistance pour l'accomplissement de la mission.", Writer.REGULAR, 10.5f);
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
            w.rightAligned(nom.toUpperCase(Locale.FRENCH), Writer.BOLD, 11, w.accent[0], w.accent[1], w.accent[2]);
        }
        w.rightAligned(fonction + ".", Writer.REGULAR, 10);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Petit moteur de rendu texte au-dessus du contenu existant d'une page
     * PDFBox (coordonnées bas-gauche, y croissant vers le haut) : habillage
     * de texte, alignement centré/droit, puces, numéros et lignes clé-valeur
     * pour le corps du document (curseur {@code y} auto-décrémenté).
     */
    private static final class Writer {
        static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        static final PDFont OBLIQUE = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        private static final float LEADING = 13.5f;

        private final PDPageContentStream cs;
        private float y;
        /** Couleur des titres : variante foncée de la couleur du thème. */
        final float[] accent;

        Writer(PDPageContentStream cs, float startY, float[] accent) {
            this.cs = cs;
            this.y = startY;
            this.accent = accent;
        }

        void gap(float amount) {
            y -= amount;
        }

        /** Ramene le curseur a {@code minY} s'il est encore au-dessus (repere bas-gauche : "au-dessus" = plus
         * grand) — pousse un bloc vers le bas de la page plutot que de le laisser suivre immediatement le
         * contenu precedent. Ne fait rien si le contenu deja ecrit occupe deja plus de place que prevu
         * (evite tout chevauchement sur une mission longue). */
        void pousserVers(float minY) {
            if (y > minY) y = minY;
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

        /** Puce "•" suivie de {@code bold} (accentué) puis {@code rest} — {@code bold} peut être vide. */
        void bulletItem(String bold, String rest, float size) {
            float x = MARGIN_LEFT + 18;
            draw("•  " + bold + rest, REGULAR, size, x, 0, 0, 0);
            y -= LEADING - 1f;
        }

        /** "{n}. {bold}{rest}" — numérotation d'une liste d'agents, alignée comme {@link #bulletItem}. */
        void numberedItem(int n, String bold, String rest, float size) {
            float x = MARGIN_LEFT + 18;
            draw(n + ".  " + bold + rest, REGULAR, size, x, 0, 0, 0);
            y -= LEADING - 1f;
        }

        void metaRow(String label, String value) {
            draw(label, BOLD, 10, MARGIN_LEFT, 0, 0, 0);
            draw(value, REGULAR, 10, MARGIN_LEFT + 145, 0, 0, 0);
            y -= LEADING;
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

        /** Dessine à {@code x} fixe, sur la ligne courante du curseur {@link #y} (repère bas-gauche PDFBox natif). */
        private void draw(String s, PDFont font, float size, float x, float r, float g, float b) {
            draw(s, font, size, x, this.y, r, g, b);
        }

        /** Dessine à une position ({@code x}, {@code yAbs}) entièrement explicite, sans lire ni écrire {@link #y}. */
        private void draw(String s, PDFont font, float size, float x, float yAbs, float r, float g, float b) {
            try {
                cs.beginText();
                cs.setFont(font, size);
                cs.setNonStrokingColor(r, g, b);
                cs.newLineAtOffset(x, yAbs);
                cs.showText(s);
                cs.endText();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

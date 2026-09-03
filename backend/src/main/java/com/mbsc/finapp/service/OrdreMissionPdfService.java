package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.AgentOrdreMission;
import com.mbsc.finapp.domain.OrdreMission;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.ParametresPaie;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
 *
 * <p>Le RCCM/ID. Nat/NIF (bandeau en haut à droite) et l'adresse/téléphone/
 * email (pied de page) sont imprimés en dur dans {@code entete_mbsc.pdf} —
 * ce PDF a été produit une fois via impression navigateur (voir ses
 * métadonnées Producer/Creator), il n'existe pas de source éditable. Pour
 * rester configurables ({@code ParametresEntreprise}, écran Paramètres),
 * ces valeurs sont donc recouvertes puis redessinées par-dessus avec la
 * valeur courante — voir {@link #overlayRegistre}. Le pied de page est un
 * aplat (recouvert avec {@code coverHtml}), mais le bandeau RCCM/ID.Nat/NIF
 * est un vrai dégradé PDF (recouvert avec {@code coverHtmlGradient}, dont
 * les paramètres sont extraits de l'objet Pattern/Shading du flux de
 * contenu du papier à en-tête, pas d'un échantillonnage pixel — voir
 * {@code RIBBON_CLAIR}/{@code RIBBON_FONCE}/{@code RIBBON_AXE_*}). Coordonnées
 * de texte obtenues avec {@code pdftotext -bbox entete_mbsc.pdf} (repère
 * haut-gauche, converties ici vers le repère bas-gauche de PDFBox) ; les
 * icônes du pied de page (repérées empiriquement par balayage de pixels sur
 * le rendu du papier statique) délimitent la largeur maximale sûre des
 * rectangles de recouvrement voisins — un recouvrement trop large les efface
 * silencieusement (peint par-dessus en couleur de fond).</p>
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

    // Le bandeau RCCM/ID.Nat/NIF est un vrai degrade PDF (ShadingType 2, pas
    // un aplat) : extrait directement du flux de contenu du papier a en-tete
    // (objet Pattern 14, cs entete_mbsc.pdf) plutot que d'un echantillonnage
    // pixel — un aplat unique, meme a la bonne couleur "moyenne", laissait
    // une desaturation visible la ou le vrai degrade est encore clair (coin
    // haut-gauche du bandeau, qui chevauche le texte RCCM). Coordonnees
    // deja converties en repere page PDFBox (bas-gauche) ; au-dela du point
    // "sombre" la teinte d'origine reste constante, un simple degrade a 2
    // teintes avec Extend=true la reproduit donc exactement (pas juste une
    // approximation) sans avoir a repliquer sa fonction de "stitching".
    private static final float[] RIBBON_CLAIR = { 0.0706f, 0.6275f, 0.4549f };
    private static final float[] RIBBON_FONCE = { 0.0392f, 0.4f, 0.3137f };
    private static final float[] RIBBON_AXE_DEBUT = { 416.64f, 838.70f };
    private static final float[] RIBBON_AXE_FIN = { 538.67f, 756.40f };
    private static final float[] FOOTER_BG = { 244f / 255, 249f / 255, 247f / 255 };
    private static final float[] WHITE = { 1f, 1f, 1f };
    private static final float[] FOOTER_TEXT = { 64f / 255, 64f / 255, 64f / 255 };
    // Position (repere bas-gauche PDFBox) ou demarre le bloc signature s'il
    // reste de la place : l'empeche de suivre immediatement le paragraphe de
    // cloture (qui laissait un grand vide en dessous sur une mission courte)
    // tout en gardant une marge confortable au-dessus du pied de page.
    private static final float SIGNATURE_ZONE_Y = 115f;

    private final ParametresPaieService parametresPaieService;
    private final ParametresEntrepriseService parametresEntrepriseService;

    public byte[] genererPdf(OrdreMission order) {
        try (InputStream is = getClass().getResourceAsStream("/pdf/entete_mbsc.pdf")) {
            if (is == null) {
                throw new IllegalStateException("Papier entête introuvable (/pdf/entete_mbsc.pdf).");
            }
            byte[] letterhead = is.readAllBytes();
            try (PDDocument doc = Loader.loadPDF(letterhead)) {
                ParametresPaie params = parametresPaieService.get();
                ParametresEntreprise entreprise = parametresEntrepriseService.obtenirEntite();
                PDPage page = doc.getPage(0);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    Writer w = new Writer(cs, START_Y);
                    overlayRegistre(w, entreprise);
                    buildTitle(w, order);
                    buildIntro(w, order, entreprise);
                    buildButSection(w, order);
                    buildMetaBlock(w, order);
                    buildClosing(w, order);
                    w.pousserVers(SIGNATURE_ZONE_Y);
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

    /** Recouvre puis redessine le RCCM/ID.Nat/NIF (bandeau) et l'adresse/téléphone/email (pied de page)
     * du papier à en-tête statique avec les valeurs courantes de {@code ParametresEntreprise} — voir la
     * javadoc de la classe. Un champ non renseigné laisse la valeur d'origine du papier visible. */
    private void overlayRegistre(Writer w, ParametresEntreprise pe) {
        if (StringUtils.hasText(pe.getRccm())) {
            w.coverHtmlGradient(452.9f, 44.5f, 578f, 53.5f, RIBBON_AXE_DEBUT, RIBBON_AXE_FIN, RIBBON_CLAIR, RIBBON_FONCE);
            w.drawAtHtmlBaseline(pe.getRccm(), Writer.REGULAR, 9, 452.9f, 53.4f, WHITE);
        }
        if (StringUtils.hasText(pe.getIdNat())) {
            w.coverHtmlGradient(454.6f, 55.7f, 578f, 64.7f, RIBBON_AXE_DEBUT, RIBBON_AXE_FIN, RIBBON_CLAIR, RIBBON_FONCE);
            w.drawAtHtmlBaseline(pe.getIdNat(), Writer.REGULAR, 9, 454.6f, 64.7f, WHITE);
        }
        if (StringUtils.hasText(pe.getNif())) {
            w.coverHtmlGradient(441.2f, 67f, 578f, 76f, RIBBON_AXE_DEBUT, RIBBON_AXE_FIN, RIBBON_CLAIR, RIBBON_FONCE);
            w.drawAtHtmlBaseline(pe.getNif(), Writer.REGULAR, 9, 441.2f, 75.9f, WHITE);
        }
        if (StringUtils.hasText(pe.getAdresse())) {
            // xMax=250, pas plus : l'icone telephone commence vers x=256 (voir la javadoc de la classe) —
            // un recouvrement plus large l'effacait silencieusement (peint par-dessus en couleur de fond).
            w.coverHtml(64f, 801f, 250f, 823f, FOOTER_BG);
            // Habillage sur 2 lignes max (180pt de large, meme espacement que les 2 lignes d'adresse du
            // papier d'origine) : sans cela, une adresse un peu longue depassait sa colonne et venait
            // chevaucher l'icone/le numero de telephone plutot que de retourner a la ligne.
            w.drawWrappedAtHtmlBaseline(pe.getAdresse(), Writer.REGULAR, 7.5f, 66.9f, 810.9f, 10.5f, 2, 180f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getTelephone())) {
            w.coverHtml(278f, 801f, 370f, 823f, FOOTER_BG);
            // Meme ligne de base que l'adresse et la 1re ligne d'email (810.9) : le papier d'origine
            // centrait verticalement le telephone entre les 2 lignes d'une adresse statique sur 2 lignes,
            // mais l'adresse dynamique (ParametresEntreprise) tient toujours sur une seule ligne — reprendre
            // cette meme base y evite un decalage visible entre le telephone et le reste du pied de page.
            w.drawAtHtmlBaseline(pe.getTelephone(), Writer.REGULAR, 7.5f, 280.3f, 810.9f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getEmail())) {
            w.coverHtml(428f, 801f, 582f, 823f, FOOTER_BG);
            // "email1 · email2" (ou un seul) -> jusqu'a 2 lignes, memes positions que le papier d'origine.
            String[] parts = pe.getEmail().split("[·;,]");
            if (parts.length > 0 && StringUtils.hasText(parts[0])) {
                w.drawAtHtmlBaseline(parts[0].trim(), Writer.REGULAR, 7.5f, 430.9f, 810.9f, FOOTER_TEXT);
            }
            if (parts.length > 1 && StringUtils.hasText(parts[1])) {
                w.drawAtHtmlBaseline(parts[1].trim(), Writer.REGULAR, 7.5f, 430.9f, 821.4f, FOOTER_TEXT);
            }
        }
    }

    private void buildTitle(Writer w, OrdreMission order) {
        String titre = order.isCollective() ? "ORDRE DE MISSION COLLECTIVE" : "ORDRE DE MISSION";
        w.centered(titre + " N°" + safe(order.getNumero()), Writer.BOLD, 13, DARK_R, DARK_G, DARK_B);
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
        w.text("BUT DE LA MISSION", Writer.BOLD, 10.5f, MARGIN_LEFT, DARK_R, DARK_G, DARK_B);
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
     * de texte, alignement centré/droit, puces, numéros et lignes clé-valeur
     * pour le corps du document (curseur {@code y} auto-décrémenté), plus
     * quelques primitives à position absolue ({@code coverHtml}/
     * {@code drawAtHtmlBaseline}, repère haut-gauche façon CSS) pour patcher
     * ponctuellement le papier à en-tête sans toucher ce curseur.
     */
    private static final class Writer {
        static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        static final PDFont OBLIQUE = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        private static final float LEADING = 13.5f;
        private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();

        private final PDPageContentStream cs;
        private float y;

        Writer(PDPageContentStream cs, float startY) {
            this.cs = cs;
            this.y = startY;
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

        /** Remplit un rectangle défini en repère haut-gauche (comme un bbox CSS/pdftotext), pas le curseur {@link #y}. */
        void coverHtml(float xMinHtml, float yMinHtml, float xMaxHtml, float yMaxHtml, float[] rgb) {
            try {
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
                float yBottom = PAGE_HEIGHT - yMaxHtml;
                cs.addRect(xMinHtml, yBottom, xMaxHtml - xMinHtml, yMaxHtml - yMinHtml);
                cs.fill();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Comme {@link #coverHtml}, mais remplit avec un dégradé axial (repère page PDFBox pour l'axe,
         * bas-gauche) au lieu d'un aplat — voir la javadoc de la classe pour l'origine des coordonnées. */
        void coverHtmlGradient(float xMinHtml, float yMinHtml, float xMaxHtml, float yMaxHtml,
                                float[] axeDebut, float[] axeFin, float[] rgbDebut, float[] rgbFin) {
            try {
                COSDictionary funcDict = new COSDictionary();
                funcDict.setInt(COSName.FUNCTION_TYPE, 2);
                funcDict.setItem(COSName.DOMAIN, cosArray(0f, 1f));
                funcDict.setItem(COSName.C0, cosArray(rgbDebut));
                funcDict.setItem(COSName.C1, cosArray(rgbFin));
                funcDict.setInt(COSName.N, 1);
                PDFunctionType2 function = new PDFunctionType2(funcDict);

                COSDictionary shadingDict = new COSDictionary();
                shadingDict.setInt(COSName.SHADING_TYPE, 2);
                PDShadingType2 shading = new PDShadingType2(shadingDict);
                shading.setColorSpace(PDDeviceRGB.INSTANCE);
                shading.setFunction(function);
                shading.setCoords(cosArray(axeDebut[0], axeDebut[1], axeFin[0], axeFin[1]));
                COSArray extend = new COSArray();
                extend.add(COSBoolean.TRUE);
                extend.add(COSBoolean.TRUE);
                shading.setExtend(extend);

                cs.saveGraphicsState();
                float yBottom = PAGE_HEIGHT - yMaxHtml;
                cs.addRect(xMinHtml, yBottom, xMaxHtml - xMinHtml, yMaxHtml - yMinHtml);
                cs.clip();
                cs.shadingFill(shading);
                cs.restoreGraphicsState();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private COSArray cosArray(float... values) {
            COSArray a = new COSArray();
            for (float v : values) a.add(new COSFloat(v));
            return a;
        }

        /** Dessine à une position absolue (repère haut-gauche, {@code yBaselineHtml} ~ bas du texte d'origine
         * — voir la javadoc de la classe), sans toucher au curseur {@link #y} utilisé par le corps du document. */
        void drawAtHtmlBaseline(String s, PDFont font, float size, float xHtml, float yBaselineHtml, float[] rgb) {
            draw(s, font, size, xHtml, PAGE_HEIGHT - yBaselineHtml, rgb[0], rgb[1], rgb[2]);
        }

        /** Comme {@link #drawAtHtmlBaseline}, mais habille {@code s} sur {@code maxWidth} et n'affiche que
         * les {@code maxLines} premières lignes (les éventuelles lignes en trop sont silencieusement omises
         * — mieux vaut un texte tronqué que débordant sur la colonne voisine). Chaque ligne suivante descend
         * de {@code interligneHtml} par rapport à la précédente (repère haut-gauche, donc vers le bas). */
        void drawWrappedAtHtmlBaseline(String s, PDFont font, float size, float xHtml, float firstBaselineHtml,
                                        float interligneHtml, int maxLines, float maxWidth, float[] rgb) {
            List<String> lines = wrap(s, font, size, maxWidth);
            for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
                drawAtHtmlBaseline(lines.get(i), font, size, xHtml, firstBaselineHtml + i * interligneHtml, rgb);
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

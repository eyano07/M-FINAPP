package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModeleEntete;
import com.mbsc.finapp.domain.enums.OrientationPapier;
import com.mbsc.finapp.domain.enums.TypePapierEntete;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import lombok.RequiredArgsConstructor;
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
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Génération du papier à en-tête vierge (aucun corps), imprimable par tout
 * utilisateur authentifié depuis sa propre page — deux types, deux
 * orientations, et un nombre de pages au choix (pages identiques répétées
 * dans un seul PDF).
 *
 * <p><b>Type général</b> : l'identité légale de l'entreprise (logo, RCCM/ID.Nat/NIF,
 * adresse/téléphone/email) dessinée selon le modèle et la couleur choisis par
 * l'administrateur (voir {@link EnteteDocumentRendu}, {@link ModeleEntete}) — le
 * même habillage que les autres PDF de l'application. À partir de la 2e page,
 * l'en-tête complet cède la place à un en-tête réduit.</p>
 *
 * <p><b>Type individuel</b> : un papier à en-tête personnel/départemental
 * (logo + nom de l'entreprise à gauche, affectation/nom/fonction de
 * l'utilisateur courant à droite, une ligne de référence avec ses initiales
 * — pas de bandeau RCCM/ID.Nat/NIF), reproduit sur le modèle fourni par
 * l'utilisateur (papier à en-tête réel de la Direction Financière) — voir
 * {@link #buildIndividuelHeader}/{@link #buildIndividuelFooter}. Sa mise en page
 * est fixe, mais il prend la couleur et le logo du thème. À partir de la 2e
 * page, l'en-tête se réduit — voir {@link #buildIndividuelHeaderReduit} — et la
 * ligne de référence n'apparaît que sur la 1re page.</p>
 */
@Service
@RequiredArgsConstructor
public class PapierEnteteService {

    private final ThemeDocumentService themeService;
    private final CurrentUserProvider currentUser;

    private static final int MAX_PAGES = 50;
    private static final float MARGIN = 55f;

    private static final float[] NOIR = { 0.1f, 0.1f, 0.1f };
    private static final float[] GRAY_TEXT = { 107f / 255, 114f / 255, 128f / 255 };
    private static final float[] WHITE = { 1f, 1f, 1f };
    private static final float[] LIGNE_SEPARATRICE = { 0.88f, 0.88f, 0.88f };

    private static final float PAYSAGE_W = PDRectangle.A4.getHeight();
    private static final float PAYSAGE_H = PDRectangle.A4.getWidth();

    public byte[] genererPdf(TypePapierEntete type, OrientationPapier orientation, int nombrePages) {
        return genererPdf(type, orientation, nombrePages, null, null);
    }

    /** {@code modele}/{@code couleur} : aperçu d'un thème non encore enregistré (null = thème des paramètres). */
    public byte[] genererPdf(TypePapierEntete type, OrientationPapier orientation, int nombrePages,
                             ModeleEntete modele, String couleur) {
        int n = Math.max(1, Math.min(nombrePages, MAX_PAGES));
        ThemeDocument theme = themeService.theme(modele, couleur);
        float pageW = orientation == OrientationPapier.PORTRAIT ? PDRectangle.A4.getWidth() : PAYSAGE_W;
        float pageH = orientation == OrientationPapier.PORTRAIT ? PDRectangle.A4.getHeight() : PAYSAGE_H;
        try (PDDocument doc = new PDDocument()) {
            if (type == TypePapierEntete.INDIVIDUEL) {
                genererIndividuel(doc, pageW, pageH, theme, currentUser.requireUser(), n);
            } else {
                genererGeneral(doc, new PDRectangle(pageW, pageH), theme, n);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Échec de la génération du papier à en-tête.", e);
        }
    }

    // ══════════════════════════ Type général ══════════════════════════════

    private void genererGeneral(PDDocument doc, PDRectangle format, ThemeDocument theme, int n) throws IOException {
        EnteteDocumentRendu rendu = new EnteteDocumentRendu(doc, theme, MARGIN);
        for (int i = 0; i < n; i++) {
            PDPage page = new PDPage(format);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                rendu.entete(cs, format, i == 0);
                float pied = rendu.pied(cs, format);
                rendu.pagination(cs, format, pied, i + 1, n);
            }
        }
    }

    // ══════════════════════════ Type individuel ═══════════════════════════


    /**
     * Papier à en-tête individuel : la 1re page a l'en-tête complet (avec la
     * ligne de référence) ; à partir de la 2e page, l'en-tête se réduit
     * (logo + nom de l'entreprise + affectation, pas de nom ni de ligne de
     * référence — une page de continuation ne doit ni répéter l'identité
     * personnelle complète, ni multiplier les numéros de référence). Même
     * pied de page sur toutes les pages. Identique en portrait et en
     * paysage (seules les dimensions de page changent).
     */
    private void genererIndividuel(PDDocument doc, float pageWidth, float pageHeight, ThemeDocument theme,
                                    User utilisateur, int n) throws IOException {
        ParametresEntreprise entreprise = theme.entreprise();
        float[] accent = theme.fonce();
        PDImageXObject logo = theme.logo() == null ? null : PDImageXObject.createFromByteArray(doc, theme.logo(), "logo");
        for (int i = 0; i < n; i++) {
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                Canvas c = new Canvas(cs, pageHeight, accent);
                if (i == 0) {
                    buildIndividuelHeader(c, pageWidth, entreprise, utilisateur, logo);
                } else {
                    buildIndividuelHeaderReduit(c, pageWidth, entreprise, utilisateur, logo);
                }
                buildIndividuelFooter(c, pageWidth, pageHeight, entreprise, utilisateur, i + 1, n);
            }
        }
    }

    /**
     * En-tête du type individuel : logo + "{1er mot de nom}" en gras noir suivi
     * du reste de {@code nom} en gras teinté (ex. "MBSC" + "Sarlu"), puis
     * {@code nomComplet} en petites capitales grises — à droite, l'affectation
     * de l'utilisateur (gras, teinté, capitales), son nom complet (gras, plus
     * grand) et sa fonction (gris) ; en dessous, une barre dégradée pleine à
     * claire puis la ligne de référence ("Réf. N° ___/{initiales}/{sigle
     * société}/{initiales affectation}/{année}", à compléter à la main — pas de
     * date "Fait à..." ici, contrairement au reste du document, sur demande
     * explicite). Reproduit le modèle réel fourni (papier à en-tête de la
     * Direction Financière).
     */
    private void buildIndividuelHeader(Canvas c, float pageWidth, ParametresEntreprise pe, User u, PDImageXObject logo) {
        float logoH = 42f;
        float logoW = logo == null ? -14f : logoH * logo.getWidth() / (float) logo.getHeight();
        if (logo != null) c.drawImageHtml(logo, MARGIN, 18f, logoW, logoH);

        float xNom = MARGIN + logoW + 14f;
        String[] motsNom = StringUtils.hasText(pe.getNom()) ? pe.getNom().trim().split("\\s+", 2) : new String[0];
        String court = motsNom.length > 0 ? motsNom[0] : "";
        c.drawAtHtmlBaseline(court, Canvas.BOLD, 19f, xNom, 40f, NOIR);
        if (motsNom.length > 1) {
            float largeurCourt = c.widthOf(court, Canvas.BOLD, 19f);
            c.drawAtHtmlBaseline(motsNom[1].toUpperCase(Locale.FRENCH), Canvas.BOLD, 14f, xNom + largeurCourt + 8f, 40f, c.accent);
        }
        if (StringUtils.hasText(pe.getNomComplet())) {
            c.drawAtHtmlBaseline(pe.getNomComplet().toUpperCase(Locale.FRENCH), Canvas.REGULAR, 8f, xNom, 55f, GRAY_TEXT);
        }

        // Bloc identite de l'utilisateur, aligne a droite : affectation (departement),
        // nom complet, fonction — dans cet ordre, comme sur le modele fourni.
        float xDroite = pageWidth - MARGIN;
        float y = 32f;
        if (StringUtils.hasText(u.getAffectation())) {
            c.drawRightAlignedHtml(u.getAffectation().toUpperCase(Locale.FRENCH), Canvas.BOLD, 11f, xDroite, y, c.accent);
            y += 18f;
        }
        String prenom = u.getPrenom() != null ? u.getPrenom().trim() : "";
        String nomMaj = u.getNom() != null ? u.getNom().trim().toUpperCase(Locale.FRENCH) : "";
        c.drawRightAlignedHtml((prenom + " " + nomMaj).trim(), Canvas.BOLD, 14f, xDroite, y, NOIR);
        y += 17f;
        if (StringUtils.hasText(u.getFonction())) {
            c.drawRightAlignedHtml(u.getFonction(), Canvas.REGULAR, 9.5f, xDroite, y, GRAY_TEXT);
        }

        // Barre degradee pleine (gauche) -> claire (droite), sous le bloc identite.
        float barY = 78f;
        float largeurUtile = pageWidth - 2 * MARGIN;
        c.coverHtmlGradient(MARGIN, barY, pageWidth - MARGIN, barY + 3f,
            new float[] { MARGIN, pageHeightAxis(c, barY) }, new float[] { MARGIN + largeurUtile * 0.42f, pageHeightAxis(c, barY) },
            c.accent, WHITE);

        // Ligne de reference : numero libre (a completer a la main) + initiales de
        // l'agent + sigle de la societe + initiales de l'affectation + annee en cours.
        StringBuilder ref = new StringBuilder("Réf. N° _______/").append(initiales(prenom + " " + nomMaj)).append('/')
            .append(court.toUpperCase(Locale.FRENCH));
        if (StringUtils.hasText(u.getAffectation())) {
            ref.append('/').append(initiales(u.getAffectation()));
        }
        ref.append('/').append(Year.now().getValue());
        c.drawAtHtmlBaseline(ref.toString(), Canvas.REGULAR, 9.5f, MARGIN, 100f, new float[] { 0.25f, 0.25f, 0.25f });
    }

    /**
     * En-tête réduit du type individuel (2e page et suivantes) : logo + nom de
     * l'entreprise sur une ligne comme {@link #buildPageReduite} (type général),
     * plus l'affectation de l'utilisateur alignée à droite sur cette même ligne —
     * mais ni son nom ni la ligne de référence, qui ne doivent apparaître qu'une
     * fois, sur la 1re page.
     */
    private void buildIndividuelHeaderReduit(Canvas c, float pageWidth, ParametresEntreprise pe, User u, PDImageXObject logo) {
        float logoH = 22f;
        float logoW = logo == null ? -10f : logoH * logo.getWidth() / (float) logo.getHeight();
        if (logo != null) c.drawImageHtml(logo, MARGIN, 14f, logoW, logoH);
        String nom = StringUtils.hasText(pe.getNomComplet()) ? pe.getNomComplet() : pe.getNom();
        c.drawAtHtmlBaseline(nom.toUpperCase(Locale.FRENCH), Canvas.BOLD, 10.5f, MARGIN + logoW + 10f, 30f, c.accent);
        if (StringUtils.hasText(u.getAffectation())) {
            c.drawRightAlignedHtml(u.getAffectation().toUpperCase(Locale.FRENCH), Canvas.BOLD, 9.5f, pageWidth - MARGIN, 30f, c.accent);
        }
        c.coverHtml(MARGIN, 42f, pageWidth - MARGIN, 42.6f, LIGNE_SEPARATRICE);
    }

    /**
     * Pied de page du type individuel (toutes les pages) : barre dégradée
     * (claire à gauche, pleine à droite — sens inverse de celle de l'en-tête,
     * effet de cadre), nom et adresse de l'entreprise à gauche, affectation/
     * e-mail/téléphone de l'utilisateur à droite. Le nom de l'utilisateur n'y
     * est volontairement PAS répété (déjà dans l'en-tête, sur demande
     * explicite — contrairement à l'affectation seule ici). Numéro de page
     * juste au-dessus de la barre dégradée (voir {@link #dessinerNumeroPage}),
     * omis si {@code totalPages <= 1}.
     */
    private void buildIndividuelFooter(Canvas c, float pageWidth, float pageHeight, ParametresEntreprise pe, User u,
                                        int numeroPage, int totalPages) {
        float barY = pageHeight - 68f;
        dessinerNumeroPage(c, pageWidth, barY - 8f, numeroPage, totalPages);
        float largeurUtile = pageWidth - 2 * MARGIN;
        c.coverHtmlGradient(MARGIN, barY, pageWidth - MARGIN, barY + 3f,
            new float[] { MARGIN + largeurUtile * 0.58f, pageHeightAxis(c, barY) }, new float[] { pageWidth - MARGIN, pageHeightAxis(c, barY) },
            WHITE, c.accent);

        StringBuilder gauche = new StringBuilder();
        if (StringUtils.hasText(pe.getNom())) gauche.append(pe.getNom());
        if (StringUtils.hasText(pe.getNomComplet())) {
            if (!gauche.isEmpty()) gauche.append(" — ");
            gauche.append(pe.getNomComplet());
        }
        c.drawAtHtmlBaseline(gauche.toString(), Canvas.BOLD, 9.5f, MARGIN, pageHeight - 50f, c.accent);
        if (StringUtils.hasText(pe.getAdresse())) {
            c.drawAtHtmlBaseline(pe.getAdresse(), Canvas.REGULAR, 8f, MARGIN, pageHeight - 38f, GRAY_TEXT);
        }

        float xDroite = pageWidth - MARGIN;
        float y = pageHeight - 52f;
        if (StringUtils.hasText(u.getAffectation())) {
            c.drawRightAlignedHtml(u.getAffectation(), Canvas.BOLD, 9.5f, xDroite, y, new float[] { 0.15f, 0.15f, 0.15f });
            y += 14f;
        }
        if (StringUtils.hasText(u.getEmail())) {
            c.drawRightAlignedHtml(u.getEmail(), Canvas.REGULAR, 8f, xDroite, y, GRAY_TEXT);
            y += 12f;
        }
        if (StringUtils.hasText(u.getTelephone())) {
            c.drawRightAlignedHtml(u.getTelephone(), Canvas.REGULAR, 8f, xDroite, y, GRAY_TEXT);
        }
    }

    /** Coordonnee Y (repere PDFBox bas-gauche) d'une ligne de base HTML {@code yHtml} — pour
     * construire l'axe (horizontal, donc a Y constant) des degrades de {@link #buildIndividuelHeader}. */
    private float pageHeightAxis(Canvas c, float yHtml) {
        return c.pageHeight - yHtml;
    }

    /** 2 lettres : initiale du prenom + initiale du nom (ex. "Bruno KALUNGA" -> "BK"). Plus generalement,
     * initiale de chaque mot separe par un espace — utilise aussi pour abreger une affectation. */
    private String initiales(String texte) {
        StringBuilder sb = new StringBuilder();
        for (String mot : texte.trim().split("\\s+")) {
            if (!mot.isEmpty()) sb.append(Character.toUpperCase(mot.charAt(0)));
        }
        return sb.toString();
    }

    /** "Page {numero} / {total}", centre horizontalement, petit gris — seulement en mode plusieurs
     * pages ({@code totalPages <= 1} : ne dessine rien, une page unique n'a pas besoin d'etre numerotee). */
    private void dessinerNumeroPage(Canvas c, float pageWidth, float baselineY, int numeroPage, int totalPages) {
        if (totalPages <= 1) return;
        String texte = "Page " + numeroPage + " / " + totalPages;
        float largeur = c.widthOf(texte, Canvas.REGULAR, 7.5f);
        c.drawAtHtmlBaseline(texte, Canvas.REGULAR, 7.5f, (pageWidth - largeur) / 2f, baselineY, GRAY_TEXT);
    }

    /**
     * Petit moteur de dessin à position absolue (repère haut-gauche façon
     * CSS/pdftotext, {@code y} croissant vers le bas), adapté de
     * {@code OrdreMissionPdfService.Writer} mais réduit aux seules
     * primitives nécessaires ici : cette page n'a pas de corps qui
     * s'écoule (pas de curseur y), seulement des éléments positionnés
     * explicitement (bandeau, pied de page, bloc d'identité).
     */
    private static final class Canvas {
        static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        private final PDPageContentStream cs;
        private final float pageHeight;
        /** Couleur d'accent : variante foncée de la couleur du thème. */
        final float[] accent;

        Canvas(PDPageContentStream cs, float pageHeight, float[] accent) {
            this.cs = cs;
            this.pageHeight = pageHeight;
            this.accent = accent;
        }

        void coverHtml(float xMinHtml, float yMinHtml, float xMaxHtml, float yMaxHtml, float[] rgb) {
            try {
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
                float yBottom = pageHeight - yMaxHtml;
                cs.addRect(xMinHtml, yBottom, xMaxHtml - xMinHtml, yMaxHtml - yMinHtml);
                cs.fill();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Degrade axial (repère page PDFBox pour l'axe, bas-gauche) au lieu d'un aplat. */
        void coverHtmlGradient(float xMinHtml, float yMinHtml, float xMaxHtml, float yMaxHtml,
                                float[] axeDebut, float[] axeFin, float[] rgbDebut, float[] rgbFin) {
            try {
                PDShadingType2 shading = buildShading(axeDebut, axeFin, rgbDebut, rgbFin);
                cs.saveGraphicsState();
                float yBottom = pageHeight - yMaxHtml;
                cs.addRect(xMinHtml, yBottom, xMaxHtml - xMinHtml, yMaxHtml - yMinHtml);
                cs.clip();
                cs.shadingFill(shading);
                cs.restoreGraphicsState();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Comme {@link #coverHtml}, mais remplit un polygone quelconque (ex. un parallélogramme
         * incliné) au lieu d'un rectangle — {@code pointsHtml} en repère haut-gauche, dans l'ordre. */
        void coverPolygonGradient(float[][] pointsHtml, float[] axeDebut, float[] axeFin, float[] rgbDebut, float[] rgbFin) {
            try {
                PDShadingType2 shading = buildShading(axeDebut, axeFin, rgbDebut, rgbFin);
                cs.saveGraphicsState();
                tracerPolygone(pointsHtml);
                cs.clip();
                cs.shadingFill(shading);
                cs.restoreGraphicsState();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void tracerPolygone(float[][] pointsHtml) throws IOException {
            cs.moveTo(pointsHtml[0][0], pageHeight - pointsHtml[0][1]);
            for (int i = 1; i < pointsHtml.length; i++) {
                cs.lineTo(pointsHtml[i][0], pageHeight - pointsHtml[i][1]);
            }
            cs.closePath();
        }

        private PDShadingType2 buildShading(float[] axeDebut, float[] axeFin, float[] rgbDebut, float[] rgbFin) {
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
            return shading;
        }

        private COSArray cosArray(float... values) {
            COSArray a = new COSArray();
            for (float v : values) a.add(new COSFloat(v));
            return a;
        }

        void drawAtHtmlBaseline(String s, PDFont font, float size, float xHtml, float yBaselineHtml, float[] rgb) {
            try {
                cs.beginText();
                cs.setFont(font, size);
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
                cs.newLineAtOffset(xHtml, pageHeight - yBaselineHtml);
                cs.showText(s);
                cs.endText();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Comme {@link #drawAtHtmlBaseline}, mais {@code xRightHtml} est le bord DROIT du texte
         * (texte aligné à droite) plutôt que son point de départ à gauche. */
        void drawRightAlignedHtml(String s, PDFont font, float size, float xRightHtml, float yBaselineHtml, float[] rgb) {
            drawAtHtmlBaseline(s, font, size, xRightHtml - widthOf(s, font, size), yBaselineHtml, rgb);
        }

        void drawWrappedAtHtmlBaseline(String s, PDFont font, float size, float xHtml, float firstBaselineHtml,
                                        float interligneHtml, int maxLines, float maxWidth, float[] rgb) {
            List<String> lines = wrap(s, font, size, maxWidth);
            for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
                drawAtHtmlBaseline(lines.get(i), font, size, xHtml, firstBaselineHtml + i * interligneHtml, rgb);
            }
        }

        /** Dessine {@code image} avec son coin haut-gauche a ({@code xHtml}, {@code yTopHtml}). */
        void drawImageHtml(PDImageXObject image, float xHtml, float yTopHtml, float width, float height) {
            try {
                float yBottom = pageHeight - yTopHtml - height;
                cs.drawImage(image, xHtml, yBottom, width, height);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        List<String> wrap(String text, PDFont font, float size, float maxWidth) {
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

        float widthOf(String s, PDFont font, float size) {
            try {
                return font.getStringWidth(s) / 1000 * size;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

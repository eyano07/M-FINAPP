package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.OrientationPapier;
import com.mbsc.finapp.domain.enums.TypePapierEntete;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
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
import java.io.InputStream;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Génération du papier à en-tête vierge (aucun corps), imprimable par tout
 * utilisateur authentifié depuis sa propre page — deux types visuellement
 * distincts, deux orientations, et un nombre de pages au choix (pages
 * identiques répétées dans un seul PDF).
 *
 * <p><b>Type général</b> : l'identité légale de l'entreprise (logo, bandeau
 * RCCM/ID.Nat/NIF, adresse/téléphone/email de la société), sur le modèle du
 * papier utilisé pour les ordres de mission. En portrait, la 1re page
 * réutilise directement le papier à en-tête statique existant
 * ({@code /pdf/entete_mbsc.pdf}, le même que {@link OrdreMissionPdfService})
 * et sa technique de recouvrement — dupliquée ici plutôt que partagée, pour
 * ne pas risquer de régression sur les ordres de mission déjà en production.
 * Le paysage n'a pas d'équivalent statique : sa 1re page est reconstruite de
 * toutes pièces (logo, bandeau en parallélogramme dégradé, accents
 * décoratifs — coordonnées relevées par balayage de pixels sur un rendu
 * haute résolution du papier statique, voir {@link #buildPaysageHeader}). À
 * partir de la 2e page, l'en-tête complet cède la place à un en-tête réduit
 * ({@link #buildPageReduite}) : logo + nom de l'entreprise sur une ligne,
 * sans bandeau — une page de continuation n'a pas besoin de répéter
 * l'identité légale complète.</p>
 *
 * <p><b>Type individuel</b> : un papier à en-tête personnel/départemental
 * (logo + nom de l'entreprise à gauche, affectation/nom/fonction de
 * l'utilisateur courant à droite, une ligne de référence avec ses initiales
 * — pas de bandeau RCCM/ID.Nat/NIF), reproduit sur le modèle fourni par
 * l'utilisateur (papier à en-tête réel de la Direction Financière) — voir
 * {@link #buildIndividuelHeader}/{@link #buildIndividuelFooter}. Identique
 * dans les deux orientations. À partir de la 2e page, même logique de
 * réduction que le type général — voir {@link #buildIndividuelHeaderReduit}
 * — mais la ligne de référence, elle, ne doit apparaître qu'une fois : sur
 * la 1re page.</p>
 */
@Service
@RequiredArgsConstructor
public class PapierEnteteService {

    private final ParametresEntrepriseService parametresEntrepriseService;
    private final CurrentUserProvider currentUser;

    private static final int MAX_PAGES = 50;
    private static final float MARGIN = 55f;

    private static final float[] NOIR = { 0.1f, 0.1f, 0.1f };
    private static final float[] DARK = { 10f / 255, 74f / 255, 66f / 255 };
    private static final float[] GRAY_TEXT = { 107f / 255, 114f / 255, 128f / 255 };
    private static final float[] FOOTER_TEXT = { 64f / 255, 64f / 255, 64f / 255 };
    private static final float[] FOOTER_BG = { 244f / 255, 249f / 255, 247f / 255 };
    private static final float[] WHITE = { 1f, 1f, 1f };
    private static final float[] LIGNE_SEPARATRICE = { 0.88f, 0.88f, 0.88f };

    // Degrade du bandeau RCCM/ID.Nat/NIF et de ses accents decoratifs (type
    // general) ainsi que des barres du type individuel : memes teintes que
    // le papier statique (extraites de son flux de contenu et par
    // echantillonnage pixel — voir OrdreMissionPdfService).
    private static final float[] RIBBON_CLAIR = { 0.0706f, 0.6275f, 0.4549f };
    private static final float[] RIBBON_FONCE = { 0.0392f, 0.4f, 0.3137f };

    // Axe du degrade du bandeau STATIQUE (papier portrait existant, type
    // general) : copie volontaire des memes constantes que
    // OrdreMissionPdfService (deja en repere page PDFBox) — le bandeau
    // paysage, reconstruit de toutes pieces sur un parallelogramme
    // different, calcule son propre axe (voir buildPaysageHeader).
    private static final float[] RIBBON_AXE_DEBUT_PORTRAIT = { 416.64f, 838.70f };
    private static final float[] RIBBON_AXE_FIN_PORTRAIT = { 538.67f, 756.40f };

    private static final float PAYSAGE_W = PDRectangle.A4.getHeight();
    private static final float PAYSAGE_H = PDRectangle.A4.getWidth();

    public byte[] genererPdf(TypePapierEntete type, OrientationPapier orientation, int nombrePages) {
        int n = Math.max(1, Math.min(nombrePages, MAX_PAGES));
        ParametresEntreprise entreprise = parametresEntrepriseService.obtenirEntite();
        try (PDDocument doc = new PDDocument()) {
            if (type == TypePapierEntete.INDIVIDUEL) {
                User utilisateur = currentUser.requireUser();
                float pageW = orientation == OrientationPapier.PORTRAIT ? PDRectangle.A4.getWidth() : PAYSAGE_W;
                float pageH = orientation == OrientationPapier.PORTRAIT ? PDRectangle.A4.getHeight() : PAYSAGE_H;
                genererIndividuel(doc, pageW, pageH, entreprise, utilisateur, n);
            } else if (orientation == OrientationPapier.PORTRAIT) {
                genererPortraitGeneral(doc, entreprise, n);
            } else {
                genererPaysageGeneral(doc, entreprise, n);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Échec de la génération du papier à en-tête.", e);
        }
    }

    // ══════════════════════════ Type général ══════════════════════════════

    // ── Portrait : page 1 = papier statique, pages suivantes = en-tête réduit ─

    private void genererPortraitGeneral(PDDocument doc, ParametresEntreprise entreprise, int n) throws IOException {
        byte[] letterhead;
        try (InputStream is = getClass().getResourceAsStream("/pdf/entete_mbsc.pdf")) {
            if (is == null) {
                throw new IllegalStateException("Papier entête introuvable (/pdf/entete_mbsc.pdf).");
            }
            letterhead = is.readAllBytes();
        }
        Icones icones = n > 1 ? chargerIcones(doc) : null;

        // Une copie fraiche du modele statique chargee PAR PAGE, fusionnee avec
        // PDFMergerUtility, plutot qu'un doc.importPage() du meme PDPage reutilise
        // pour chaque page : importPage() ne recopie pas correctement la police
        // integree (LiberationSans) du papier statique d'une page vers une autre
        // (PDF illisible pour des lecteurs stricts — endstream/police introuvable
        // a la relecture, constate a l'implementation). PDFMergerUtility fusionne
        // des documents complets et autonomes, chacun construit avec la meme
        // technique deja fiable qu'OrdreMissionPdfService (chargement + surcharge
        // en place, sans jamais copier un PDPage entre documents).
        PDFMergerUtility merger = new PDFMergerUtility();
        try (PDDocument page1 = Loader.loadPDF(letterhead)) {
            try (PDPageContentStream cs = new PDPageContentStream(
                    page1, page1.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true)) {
                Canvas c = new Canvas(cs, PDRectangle.A4.getHeight());
                overlayRegistrePortrait(c, entreprise);
                dessinerNumeroPage(c, PDRectangle.A4.getWidth(), 791f, 1, n);
            }
            merger.appendDocument(doc, page1);
        }
        for (int i = 1; i < n; i++) {
            buildPageReduite(doc, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight(), entreprise, icones, i + 1, n);
        }
    }

    /** Reproduit le recouvrement du papier statique fait par {@code OrdreMissionPdfService.overlayRegistre}
     * (mêmes coordonnées, voir sa javadoc) avec les valeurs courantes de l'entreprise. */
    private void overlayRegistrePortrait(Canvas c, ParametresEntreprise pe) {
        if (StringUtils.hasText(pe.getRccm())) {
            c.coverHtmlGradient(452.9f, 44.5f, 578f, 53.5f, RIBBON_AXE_DEBUT_PORTRAIT, RIBBON_AXE_FIN_PORTRAIT, RIBBON_CLAIR, RIBBON_FONCE);
            c.drawAtHtmlBaseline(pe.getRccm(), Canvas.REGULAR, 9, 452.9f, 53.4f, WHITE);
        }
        if (StringUtils.hasText(pe.getIdNat())) {
            c.coverHtmlGradient(454.6f, 55.7f, 578f, 64.7f, RIBBON_AXE_DEBUT_PORTRAIT, RIBBON_AXE_FIN_PORTRAIT, RIBBON_CLAIR, RIBBON_FONCE);
            c.drawAtHtmlBaseline(pe.getIdNat(), Canvas.REGULAR, 9, 454.6f, 64.7f, WHITE);
        }
        if (StringUtils.hasText(pe.getNif())) {
            c.coverHtmlGradient(441.2f, 67f, 578f, 76f, RIBBON_AXE_DEBUT_PORTRAIT, RIBBON_AXE_FIN_PORTRAIT, RIBBON_CLAIR, RIBBON_FONCE);
            c.drawAtHtmlBaseline(pe.getNif(), Canvas.REGULAR, 9, 441.2f, 75.9f, WHITE);
        }
        if (StringUtils.hasText(pe.getAdresse())) {
            c.coverHtml(64f, 801f, 250f, 823f, FOOTER_BG);
            c.drawWrappedAtHtmlBaseline(pe.getAdresse(), Canvas.REGULAR, 7.5f, 66.9f, 810.9f, 10.5f, 2, 180f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getTelephone())) {
            c.coverHtml(278f, 801f, 370f, 823f, FOOTER_BG);
            c.drawAtHtmlBaseline(pe.getTelephone(), Canvas.REGULAR, 7.5f, 280.3f, 810.9f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getEmail())) {
            c.coverHtml(428f, 801f, 582f, 823f, FOOTER_BG);
            String[] parts = pe.getEmail().split("[·;,]");
            if (parts.length > 0 && StringUtils.hasText(parts[0])) {
                c.drawAtHtmlBaseline(parts[0].trim(), Canvas.REGULAR, 7.5f, 430.9f, 810.9f, FOOTER_TEXT);
            }
            if (parts.length > 1 && StringUtils.hasText(parts[1])) {
                c.drawAtHtmlBaseline(parts[1].trim(), Canvas.REGULAR, 7.5f, 430.9f, 821.4f, FOOTER_TEXT);
            }
        }
    }

    // ── Paysage : page 1 = reconstitution fidele du portrait, pages suivantes = en-tête réduit ─

    private void genererPaysageGeneral(PDDocument doc, ParametresEntreprise entreprise, int n) throws IOException {
        Icones icones = chargerIcones(doc);

        PDPage page1 = new PDPage(new PDRectangle(PAYSAGE_W, PAYSAGE_H));
        doc.addPage(page1);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
            Canvas c = new Canvas(cs, PAYSAGE_H);
            buildPaysageHeader(c, entreprise, icones.logo());
            buildPiedDePage(c, PAYSAGE_W, PAYSAGE_H, entreprise, icones, 1, n);
        }

        for (int i = 1; i < n; i++) {
            buildPageReduite(doc, PAYSAGE_W, PAYSAGE_H, entreprise, icones, i + 1, n);
        }
    }

    private record Icones(PDImageXObject logo, PDImageXObject pin, PDImageXObject phone, PDImageXObject email) {}

    private Icones chargerIcones(PDDocument doc) throws IOException {
        return new Icones(
            loadImage(doc, "/pdf/papier_entete_logo.png"),
            loadImage(doc, "/pdf/papier_entete_icon_pin.png"),
            loadImage(doc, "/pdf/papier_entete_icon_phone.png"),
            loadImage(doc, "/pdf/papier_entete_icon_email.png")
        );
    }

    private PDImageXObject loadImage(PDDocument doc, String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Ressource introuvable : " + resourcePath);
            }
            return PDImageXObject.createFromByteArray(doc, is.readAllBytes(), resourcePath);
        }
    }

    /**
     * En-tête complet de la 1re page en paysage (type général), reconstruit pour
     * reproduire fidèlement celui du papier portrait statique : logo, nom de
     * l'entreprise sur 1-2 lignes suivi du sigle (dérivé de
     * {@code ParametresEntreprise.nom}, ex. "MBSC Sarlu" -> "Sarlu" — pas de
     * champ dédié pour ce sigle), bandeau RCCM/ID.Nat/NIF en parallélogramme
     * dégradé avec sa fine ligne d'accent au-dessus, et le petit accent
     * décoratif à gauche du logo. Toutes les coordonnées ci-dessous ont été
     * relevées par balayage de pixels sur un rendu haute résolution du
     * papier statique (même méthode que pour les icônes du pied de page).
     */
    private void buildPaysageHeader(Canvas c, ParametresEntreprise pe, PDImageXObject logo) {
        // Accent decoratif a gauche du logo : petit parallelogramme colle au bord
        // gauche de la page, degrade vertical clair (haut) -> fonce (bas).
        c.coverPolygonGradient(
            new float[][] { { 0, 44 }, { 31.4f, 44 }, { 25.4f, 69 }, { 0, 69 } },
            new float[] { 15, 44 }, new float[] { 15, 69 }, RIBBON_CLAIR, RIBBON_FONCE);

        float logoH = 56f;
        float logoW = logoH * logo.getWidth() / (float) logo.getHeight();
        c.drawImageHtml(logo, MARGIN, 26f, logoW, logoH);

        // Bandeau RCCM/ID.Nat/NIF : parallelogramme plein bord (bord droit = bord de
        // page, comme le papier statique), bord gauche incline de SLANT sur la hauteur.
        float rXMax = PAYSAGE_W;
        float rWidth = 227f;
        float rYMin = 22f;
        float rYMax = 75.5f;
        float slant = 13f;
        float rXMinHaut = rXMax - rWidth + slant;
        float rXMinBas = rXMax - rWidth;
        c.coverPolygonGradient(
            new float[][] { { rXMinHaut, rYMin }, { rXMax, rYMin }, { rXMax, rYMax }, { rXMinBas, rYMax } },
            new float[] { rXMinHaut + 20, rYMin }, new float[] { rXMinBas + 60, rYMax }, RIBBON_CLAIR, RIBBON_FONCE);
        // Fine ligne d'accent juste au-dessus du bandeau, meme couleur foncee, plein bord.
        c.coverHtml(rXMinHaut + 19, rYMin - 8.2f, PAYSAGE_W, rYMin - 4f, RIBBON_FONCE);

        String nom = StringUtils.hasText(pe.getNomComplet()) ? pe.getNomComplet() : pe.getNom();
        float xNom = MARGIN + logoW + 16f;
        float largeurDispo = rXMinBas - xNom - 14f;
        List<String> lignes = c.wrap(nom.toUpperCase(Locale.FRENCH), Canvas.BOLD, 15f, largeurDispo);
        float y = 48f;
        for (int i = 0; i < lignes.size(); i++) {
            String ligne = lignes.get(i);
            c.drawAtHtmlBaseline(ligne, Canvas.BOLD, 15f, xNom, y, DARK);
            // Sigle (ex. "Sarlu" dans "MBSC Sarlu") accolé à la dernière ligne du nom,
            // comme sur le papier statique — derive de `nom`, pas d'un champ dedie.
            if (i == lignes.size() - 1) {
                String sigle = sigleEntreprise(pe);
                if (sigle != null) {
                    float largeurLigne = c.widthOf(ligne, Canvas.BOLD, 15f);
                    c.drawAtHtmlBaseline(sigle.toUpperCase(Locale.FRENCH), Canvas.REGULAR, 13f, xNom + largeurLigne + 8f, y, GRAY_TEXT);
                }
            }
            y += 19f;
        }

        float ry = 42f;
        ry = drawLabelValue(c, "RCCM", pe.getRccm(), rXMinHaut + 12f, ry);
        ry = drawLabelValue(c, "ID. Nat", pe.getIdNat(), rXMinHaut + 12f, ry);
        drawLabelValue(c, "NIF", pe.getNif(), rXMinHaut + 12f, ry);

        // Filet fin sous l'entete, comme sur le papier portrait statique.
        c.coverHtml(MARGIN, 95f, PAYSAGE_W - MARGIN, 95.6f, LIGNE_SEPARATRICE);
    }

    /** 2e mot et suivants de {@code nom} (ex. "MBSC Sarlu" -> "Sarlu"), {@code null} si un seul mot. */
    private String sigleEntreprise(ParametresEntreprise pe) {
        if (!StringUtils.hasText(pe.getNom())) return null;
        String[] mots = pe.getNom().trim().split("\\s+", 2);
        return mots.length > 1 ? mots[1] : null;
    }

    /** "{label} : {value}" (label en gras, blanc) — ignore silencieusement si value est vide (comme le
     * papier portrait statique). Retourne la ligne de base suivante (+14pt), que value soit vide ou non,
     * pour garder les 3 lignes du bandeau a position fixe. */
    private float drawLabelValue(Canvas c, String label, String value, float x, float baselineY) {
        if (StringUtils.hasText(value)) {
            String prefixe = label + " : ";
            c.drawAtHtmlBaseline(prefixe, Canvas.BOLD, 9f, x, baselineY, WHITE);
            float largeurPrefixe = c.widthOf(prefixe, Canvas.BOLD, 9f);
            c.drawAtHtmlBaseline(value, Canvas.REGULAR, 9f, x + largeurPrefixe, baselineY, WHITE);
        }
        return baselineY + 14f;
    }

    /**
     * Pied de page (icônes + adresse/téléphone/e-mail de l'entreprise, type général
     * uniquement) : utilisé pour la page 1 en paysage et pour toutes les pages
     * réduites. Hauteur volontairement compacte (44pt, contre 74pt dans une 1re
     * version jugée trop imposante) avec le même petit accent décoratif que le
     * pied de page statique. Numéro de page juste au-dessus (voir {@link
     * #dessinerNumeroPage}), omis si {@code totalPages <= 1}.
     */
    private void buildPiedDePage(Canvas c, float pageWidth, float pageHeight, ParametresEntreprise pe, Icones icones,
                                  int numeroPage, int totalPages) {
        float bandBottom = pageHeight - 6f;
        float bandTop = bandBottom - 44f;
        dessinerNumeroPage(c, pageWidth, bandTop - 8f, numeroPage, totalPages);
        c.coverHtml(0f, bandTop, pageWidth, bandBottom, FOOTER_BG);
        // Accent decoratif a gauche, meme esprit que celui de l'entete (degrade vertical).
        float accentH = 20f;
        float accentY = bandTop + (44f - accentH) / 2f;
        c.coverPolygonGradient(
            new float[][] { { 0, accentY }, { 24.5f, accentY }, { 19.9f, accentY + accentH }, { 0, accentY + accentH } },
            new float[] { 12, accentY }, new float[] { 12, accentY + accentH }, RIBBON_CLAIR, RIBBON_FONCE);

        float iconSize = 16f;
        float iconY = bandTop + (44f - iconSize) / 2f;
        float usable = pageWidth - 2 * MARGIN;
        float colW = usable / 3f;
        float col1 = MARGIN;
        float col2 = MARGIN + colW;
        float col3 = MARGIN + 2 * colW;

        if (StringUtils.hasText(pe.getAdresse())) {
            c.drawImageHtml(icones.pin(), col1, iconY, iconSize, iconSize);
            c.drawWrappedAtHtmlBaseline(pe.getAdresse(), Canvas.REGULAR, 7f, col1 + iconSize + 8f,
                iconY + 3f, 9.5f, 2, colW - iconSize - 16f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getTelephone())) {
            c.drawImageHtml(icones.phone(), col2, iconY, iconSize, iconSize);
            c.drawAtHtmlBaseline(pe.getTelephone(), Canvas.REGULAR, 7.5f, col2 + iconSize + 8f, iconY + 10f, FOOTER_TEXT);
        }
        if (StringUtils.hasText(pe.getEmail())) {
            c.drawImageHtml(icones.email(), col3, iconY, iconSize, iconSize);
            String[] parts = pe.getEmail().split("[·;,]");
            float ey = iconY + 3f;
            for (int i = 0; i < parts.length && i < 2; i++) {
                if (StringUtils.hasText(parts[i])) {
                    c.drawAtHtmlBaseline(parts[i].trim(), Canvas.REGULAR, 7f, col3 + iconSize + 8f, ey, FOOTER_TEXT);
                }
                ey += 9.5f;
            }
        }
    }

    /**
     * Page "de continuation" du type général (2e page et suivantes en mode
     * plusieurs pages) : en-tête réduit à un simple logo + nom de l'entreprise
     * sur une ligne (pas de bandeau RCCM/ID.Nat/NIF, pas d'accent décoratif —
     * inutile de répéter l'identité légale complète sur chaque page), même
     * pied de page que la 1re page.
     */
    private void buildPageReduite(PDDocument doc, float pageWidth, float pageHeight, ParametresEntreprise entreprise,
                                   Icones icones, int numeroPage, int totalPages) throws IOException {
        PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            Canvas c = new Canvas(cs, pageHeight);

            float logoH = 22f;
            float logoW = logoH * icones.logo().getWidth() / (float) icones.logo().getHeight();
            c.drawImageHtml(icones.logo(), MARGIN, 14f, logoW, logoH);
            String nom = StringUtils.hasText(entreprise.getNomComplet()) ? entreprise.getNomComplet() : entreprise.getNom();
            c.drawAtHtmlBaseline(nom.toUpperCase(Locale.FRENCH), Canvas.BOLD, 10.5f, MARGIN + logoW + 10f, 30f, DARK);
            c.coverHtml(MARGIN, 42f, pageWidth - MARGIN, 42.6f, LIGNE_SEPARATRICE);

            buildPiedDePage(c, pageWidth, pageHeight, entreprise, icones, numeroPage, totalPages);
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
    private void genererIndividuel(PDDocument doc, float pageWidth, float pageHeight, ParametresEntreprise entreprise,
                                    User utilisateur, int n) throws IOException {
        PDImageXObject logo = loadImage(doc, "/pdf/papier_entete_logo.png");
        for (int i = 0; i < n; i++) {
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                Canvas c = new Canvas(cs, pageHeight);
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
        float logoW = logoH * logo.getWidth() / (float) logo.getHeight();
        c.drawImageHtml(logo, MARGIN, 18f, logoW, logoH);

        float xNom = MARGIN + logoW + 14f;
        String[] motsNom = StringUtils.hasText(pe.getNom()) ? pe.getNom().trim().split("\\s+", 2) : new String[0];
        String court = motsNom.length > 0 ? motsNom[0] : "";
        c.drawAtHtmlBaseline(court, Canvas.BOLD, 19f, xNom, 40f, NOIR);
        if (motsNom.length > 1) {
            float largeurCourt = c.widthOf(court, Canvas.BOLD, 19f);
            c.drawAtHtmlBaseline(motsNom[1].toUpperCase(Locale.FRENCH), Canvas.BOLD, 14f, xNom + largeurCourt + 8f, 40f, RIBBON_FONCE);
        }
        if (StringUtils.hasText(pe.getNomComplet())) {
            c.drawAtHtmlBaseline(pe.getNomComplet().toUpperCase(Locale.FRENCH), Canvas.REGULAR, 8f, xNom, 55f, GRAY_TEXT);
        }

        // Bloc identite de l'utilisateur, aligne a droite : affectation (departement),
        // nom complet, fonction — dans cet ordre, comme sur le modele fourni.
        float xDroite = pageWidth - MARGIN;
        float y = 32f;
        if (StringUtils.hasText(u.getAffectation())) {
            c.drawRightAlignedHtml(u.getAffectation().toUpperCase(Locale.FRENCH), Canvas.BOLD, 11f, xDroite, y, RIBBON_FONCE);
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
            RIBBON_FONCE, WHITE);

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
        float logoW = logoH * logo.getWidth() / (float) logo.getHeight();
        c.drawImageHtml(logo, MARGIN, 14f, logoW, logoH);
        String nom = StringUtils.hasText(pe.getNomComplet()) ? pe.getNomComplet() : pe.getNom();
        c.drawAtHtmlBaseline(nom.toUpperCase(Locale.FRENCH), Canvas.BOLD, 10.5f, MARGIN + logoW + 10f, 30f, DARK);
        if (StringUtils.hasText(u.getAffectation())) {
            c.drawRightAlignedHtml(u.getAffectation().toUpperCase(Locale.FRENCH), Canvas.BOLD, 9.5f, pageWidth - MARGIN, 30f, RIBBON_FONCE);
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
            WHITE, RIBBON_FONCE);

        StringBuilder gauche = new StringBuilder();
        if (StringUtils.hasText(pe.getNom())) gauche.append(pe.getNom());
        if (StringUtils.hasText(pe.getNomComplet())) {
            if (!gauche.isEmpty()) gauche.append(" — ");
            gauche.append(pe.getNomComplet());
        }
        c.drawAtHtmlBaseline(gauche.toString(), Canvas.BOLD, 9.5f, MARGIN, pageHeight - 50f, RIBBON_FONCE);
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

        Canvas(PDPageContentStream cs, float pageHeight) {
            this.cs = cs;
            this.pageHeight = pageHeight;
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

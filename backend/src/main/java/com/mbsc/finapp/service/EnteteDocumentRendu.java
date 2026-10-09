package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.ModeleEntete;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dessine le papier à en-tête (en-tête et pied de page) d'une page PDF selon le modèle et la couleur du
 * thème ({@link ThemeDocument}) : même habillage pour tous les documents générés par le serveur. Le corps
 * du document reste à la charge de l'appelant, entre {@link #entete} (qui renvoie la hauteur occupée en haut
 * de page) et {@link #pied} (hauteur occupée en bas).
 *
 * <p>Une instance par document : le logo n'y est intégré qu'une fois, quel que soit le nombre de pages.
 * Coordonnées internes en repère « haut-gauche » (y croissant vers le bas, comme en CSS), converties vers
 * le repère bas-gauche de PDFBox au moment du dessin.</p>
 */
public final class EnteteDocumentRendu {

    public static final PDFont REG = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    public static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    public static final PDFont ITAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final float[] BLANC = {1, 1, 1};
    private static final float[] NOIR = {0.07f, 0.09f, 0.15f};
    private static final float[] GRIS = {0.42f, 0.45f, 0.5f};
    private static final float[] GRIS_FONCE = {0.25f, 0.27f, 0.31f};
    private static final float[] FILET = {0.86f, 0.87f, 0.89f};

    private final PDDocument doc;
    private final ThemeDocument theme;
    private final float marge;
    private PDImageXObject logo;
    private boolean logoCharge;

    // Page en cours de dessin
    private PDPageContentStream cs;
    private float w;
    private float h;

    public EnteteDocumentRendu(PDDocument doc, ThemeDocument theme, float marge) {
        this.doc = doc;
        this.theme = theme;
        this.marge = marge;
    }

    public ThemeDocument theme() {
        return theme;
    }

    // =====================================================================================
    // En-tête
    // =====================================================================================

    /**
     * Dessine l'en-tête de la page. {@code complet} : en-tête de 1re page (identité légale complète) ;
     * sinon en-tête réduit des pages suivantes. Renvoie la hauteur occupée depuis le haut de la page.
     */
    public float entete(PDPageContentStream cs, PDRectangle page, boolean complet) throws IOException {
        this.cs = cs;
        this.w = page.getWidth();
        this.h = page.getHeight();
        if (theme.modele() == ModeleEntete.LATERAL) {
            barreLaterale();
        }
        if (!complet) {
            return enteteReduit();
        }
        return switch (theme.modele()) {
            case BANDEAU -> enteteBandeau();
            case EPURE -> enteteEpure();
            case CENTRE -> enteteCentre();
            case LATERAL -> enteteLateral();
            case ENCADRE -> enteteEncadre();
            default -> enteteClassique();
        };
    }

    private float enteteClassique() throws IOException {
        // Petit accent incliné collé au bord gauche
        polygoneDegrade(new float[][] {{0, 44}, {28, 44}, {22, 69}, {0, 69}}, 14, 44, 14, 69, theme.couleur(), theme.fonce());

        float largeurRuban = Math.min(230f, w * 0.38f);
        float pente = 13f;
        float rubanHaut = w - largeurRuban + pente;
        float rubanBas = w - largeurRuban;
        polygoneDegrade(new float[][] {{rubanHaut, 22}, {w, 22}, {w, 78}, {rubanBas, 78}},
            rubanHaut + 20, 22, rubanBas + 60, 78, theme.couleur(), theme.fonce());
        rect(rubanHaut + 19, 13.5f, w, 17.5f, theme.fonce());
        float y = 40;
        for (String[] id : identifiants()) {
            libelleValeur(id[0], id[1], 8.5f, rubanHaut + 12, y, BLANC, BLANC);
            y += 14;
        }

        float xTexte = logo(marge, 24, 54) + 14;
        blocNom(xTexte, 46, rubanBas - xTexte - 14, 15, theme.fonce());
        rect(marge, 92, w - marge, 92.6f, FILET);
        return 102;
    }

    private float enteteBandeau() throws IOException {
        rect(0, 0, w, 80, theme.couleur());
        rect(0, 80, w, 83, theme.fonce());
        rect(marge, 14, marge + 52, 66, BLANC);
        logoDans(marge + 4, 18, 44, 44);
        float x = marge + 66;
        float largeurIds = 0;
        List<String> ids = identifiantsTexte();
        for (String id : ids) largeurIds = Math.max(largeurIds, largeur(id, REG, 8.5f));
        float y = 32;
        for (String id : ids) {
            texteDroite(id, REG, 8.5f, w - marge, y, BLANC);
            y += 13;
        }
        float largeurNom = w - marge - largeurIds - 20 - x;
        List<String> lignes = couper(nom().toUpperCase(Locale.FRENCH), BOLD, 16, largeurNom);
        float yn = 38;
        for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
            texte(l, BOLD, 16, x, yn, BLANC);
            yn += 18;
        }
        String sous = sousTitre();
        if (sous != null) texte(tronquer(sous, REG, 8.5f, largeurNom), REG, 8.5f, x, yn - 2, BLANC);
        return 98;
    }

    private float enteteEpure() throws IOException {
        float xTexte = logo(marge, 20, 42) + 14;
        List<String> ids = identifiantsTexte();
        float largeurIds = 0;
        for (String id : ids) largeurIds = Math.max(largeurIds, largeur(id, REG, 8));
        float y = 32;
        for (String id : ids) {
            texteDroite(id, REG, 8, w - marge, y, GRIS);
            y += 12;
        }
        blocNom(xTexte, 40, w - marge - largeurIds - 20 - xTexte, 17, theme.couleur());
        rect(marge, 82, w - marge, 83, theme.couleur());
        return 94;
    }

    private float enteteCentre() throws IOException {
        float hLogo = 40;
        float largeurLogo = largeurLogo(hLogo);
        logo((w - largeurLogo) / 2, 14, hLogo);
        texteCentre(tronquer(nom().toUpperCase(Locale.FRENCH), BOLD, 15, w - 2 * marge), BOLD, 15, 72, theme.fonce());
        float y = 84;
        String sous = sousTitre();
        if (sous != null) {
            texteCentre(tronquer(sous, REG, 8.5f, w - 2 * marge), REG, 8.5f, y, GRIS);
            y += 12;
        }
        String ids = String.join("   •   ", identifiantsTexte());
        if (!ids.isEmpty()) {
            texteCentre(tronquer(ids, REG, 8, w - 2 * marge), REG, 8, y, GRIS_FONCE);
            y += 8;
        }
        rect(marge, y + 2, w - marge, y + 4, theme.couleur());
        rect(marge, y + 6, w - marge, y + 6.6f, theme.couleur());
        return y + 16;
    }

    private float enteteLateral() throws IOException {
        float xTexte = logo(marge, 22, 48) + 14;
        List<String[]> ids = identifiants();
        float largeurIds = 0;
        for (String[] id : ids) largeurIds = Math.max(largeurIds, largeur(id[0] + " : " + id[1], REG, 8));
        float y = 34;
        for (String[] id : ids) {
            libelleValeur(id[0], id[1], 8, w - marge - largeurIds, y, theme.fonce(), GRIS_FONCE);
            y += 12;
        }
        if (!ids.isEmpty()) rect(w - marge - largeurIds - 8, 25, w - marge - largeurIds - 6, y - 8, theme.couleur());
        blocNom(xTexte, 44, w - marge - largeurIds - 24 - xTexte, 16, theme.couleur());
        rect(marge, 86, w - marge, 86.6f, FILET);
        return 96;
    }

    private float enteteEncadre() throws IOException {
        float gauche = marge - 10;
        float droite = w - marge + 10;
        cadre(gauche, 16, droite, 90, theme.clair(), theme.couleur(), 1.2f);
        float xTexte = logo(marge, 26, 52) + 14;
        List<String> ids = identifiantsTexte();
        float largeurIds = 0;
        for (String id : ids) largeurIds = Math.max(largeurIds, largeur(id, REG, 8));
        float y = 42;
        for (String id : ids) {
            texteDroite(id, REG, 8, w - marge, y, GRIS_FONCE);
            y += 12;
        }
        blocNom(xTexte, 46, w - marge - largeurIds - 20 - xTexte, 15, theme.fonce());
        return 104;
    }

    private float enteteReduit() throws IOException {
        String nom = nom().toUpperCase(Locale.FRENCH);
        switch (theme.modele()) {
            case BANDEAU -> {
                rect(0, 0, w, 34, theme.couleur());
                texte(tronquer(nom, BOLD, 10.5f, w - 2 * marge), BOLD, 10.5f, marge, 22, BLANC);
                return 46;
            }
            case ENCADRE -> {
                cadre(marge - 10, 10, w - marge + 10, 40, theme.clair(), theme.couleur(), 1f);
                float x = logo(marge, 14, 22) + 10;
                texte(tronquer(nom, BOLD, 10.5f, w - marge - x), BOLD, 10.5f, x, 29, theme.fonce());
                return 52;
            }
            case CENTRE -> {
                texteCentre(tronquer(nom, BOLD, 10.5f, w - 2 * marge), BOLD, 10.5f, 28, theme.fonce());
                rect(marge, 36, w - marge, 37.5f, theme.couleur());
                rect(marge, 39.5f, w - marge, 40, theme.couleur());
                return 50;
            }
            default -> {
                float x = logo(marge, 14, 22) + 10;
                texte(tronquer(nom, BOLD, 10.5f, w - marge - x), BOLD, 10.5f, x, 30,
                    theme.modele() == ModeleEntete.CLASSIQUE ? theme.fonce() : theme.couleur());
                rect(marge, 42, w - marge, theme.modele() == ModeleEntete.EPURE ? 43 : 42.6f,
                    theme.modele() == ModeleEntete.CLASSIQUE ? FILET : theme.couleur());
                return 52;
            }
        }
    }

    private void barreLaterale() throws IOException {
        rect(0, 0, 12, h, theme.couleur());
        rect(12, 0, 16, h, theme.clair());
    }

    /** Nom (gras, 1 ou 2 lignes), puis nom complet / slogan en dessous. */
    private void blocNom(float x, float baseline, float largeurMax, float taille, float[] couleur) throws IOException {
        List<String> lignes = couper(nom().toUpperCase(Locale.FRENCH), BOLD, taille, Math.max(largeurMax, 80));
        float y = baseline;
        for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
            texte(l, BOLD, taille, x, y, couleur);
            y += taille + 3;
        }
        String sous = sousTitre();
        if (sous != null) {
            texte(tronquer(sous, REG, 8.5f, Math.max(largeurMax, 80)), REG, 8.5f, x, y - 1, GRIS);
            y += 11;
        }
        ParametresEntreprise e = theme.entreprise();
        if (present(e.getSlogan()) && !e.getSlogan().equals(sous)) {
            texte(tronquer(e.getSlogan(), ITAL, 8, Math.max(largeurMax, 80)), ITAL, 8, x, y, GRIS);
        }
    }

    // =====================================================================================
    // Pied de page
    // =====================================================================================

    /** Dessine le pied de page (coordonnées de l'entreprise). Renvoie la hauteur occupée depuis le bas. */
    public float pied(PDPageContentStream cs, PDRectangle page) throws IOException {
        this.cs = cs;
        this.w = page.getWidth();
        this.h = page.getHeight();
        List<String> contacts = contacts();
        switch (theme.modele()) {
            case BANDEAU -> {
                rect(0, h - 34, w, h, theme.couleur());
                lignesCentrees(contacts, h - 20, BLANC);
                return 42;
            }
            case EPURE -> {
                rect(marge, h - 42, w - marge, h - 41.4f, FILET);
                lignesCentrees(contacts, h - 28, GRIS);
                return 48;
            }
            case CENTRE -> {
                rect(marge, h - 46, w - marge, h - 45.5f, theme.couleur());
                rect(marge, h - 43.5f, w - marge, h - 41.5f, theme.couleur());
                lignesCentrees(contacts, h - 28, GRIS_FONCE);
                return 52;
            }
            case LATERAL -> {
                rect(marge, h - 40, marge + 36, h - 38, theme.couleur());
                String ligne = String.join("   •   ", contacts);
                List<String> lignes = couper(ligne, REG, 7.5f, w - 2 * marge);
                float y = h - 26;
                for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
                    texte(l, REG, 7.5f, marge, y, GRIS_FONCE);
                    y += 10;
                }
                return 48;
            }
            case ENCADRE -> {
                cadre(marge - 10, h - 46, w - marge + 10, h - 14, theme.clair(), theme.couleur(), 1f);
                lignesCentrees(contacts, h - 32, GRIS_FONCE);
                return 54;
            }
            default -> {
                rect(0, h - 50, w, h - 6, theme.clair());
                polygoneDegrade(new float[][] {{0, h - 38}, {24, h - 38}, {20, h - 18}, {0, h - 18}},
                    12, h - 38, 12, h - 18, theme.couleur(), theme.fonce());
                piedColonnes(h - 50, h - 6);
                return 58;
            }
        }
    }

    /** Pied du modèle classique : adresse, téléphone et e-mail en trois colonnes, avec une pastille de couleur. */
    private void piedColonnes(float haut, float bas) throws IOException {
        ParametresEntreprise e = theme.entreprise();
        String[][] colonnes = {
            {"Adresse", e.getAdresse()}, {"Téléphone", e.getTelephone()}, {"E-mail", e.getEmail()},
        };
        float largeurCol = (w - 2 * marge) / 3f;
        float milieu = (haut + bas) / 2f;
        for (int i = 0; i < 3; i++) {
            if (!present(colonnes[i][1])) continue;
            float x = marge + i * largeurCol;
            rect(x, milieu - 9, x + 3, milieu + 9, theme.couleur());
            texte(colonnes[i][0].toUpperCase(Locale.FRENCH), BOLD, 6.5f, x + 9, milieu - 3, theme.fonce());
            List<String> lignes = couper(colonnes[i][1].replaceAll("\\s*[·;]\\s*", " · "), REG, 7.5f, largeurCol - 16);
            float y = milieu + 7;
            for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
                texte(l, REG, 7.5f, x + 9, y, GRIS_FONCE);
                y += 9;
            }
        }
    }

    /** « Page n / total », centré juste au-dessus du pied de page ; rien pour un document d'une seule page. */
    public void pagination(PDPageContentStream cs, PDRectangle page, float hauteurPied, int numero, int total) throws IOException {
        if (total <= 1) return;
        this.cs = cs;
        this.w = page.getWidth();
        this.h = page.getHeight();
        texteCentre("Page " + numero + " / " + total, REG, 7.5f, h - hauteurPied - 4, GRIS);
    }

    // =====================================================================================
    // Données
    // =====================================================================================

    private String nom() {
        ParametresEntreprise e = theme.entreprise();
        return present(e.getNom()) ? e.getNom().trim() : "";
    }

    /** Raison sociale complète si elle diffère du nom, sinon le slogan. */
    private String sousTitre() {
        ParametresEntreprise e = theme.entreprise();
        if (present(e.getNomComplet()) && !e.getNomComplet().trim().equalsIgnoreCase(nom())) return e.getNomComplet().trim();
        return present(e.getSlogan()) ? e.getSlogan().trim() : null;
    }

    private List<String[]> identifiants() {
        ParametresEntreprise e = theme.entreprise();
        List<String[]> ids = new ArrayList<>();
        if (present(e.getRccm())) ids.add(new String[] {"RCCM", e.getRccm().trim()});
        if (present(e.getIdNat())) ids.add(new String[] {"ID. Nat", e.getIdNat().trim()});
        if (present(e.getNif())) ids.add(new String[] {"NIF", e.getNif().trim()});
        return ids;
    }

    private List<String> identifiantsTexte() {
        return identifiants().stream().map(id -> id[0] + " : " + id[1]).toList();
    }

    private List<String> contacts() {
        ParametresEntreprise e = theme.entreprise();
        List<String> c = new ArrayList<>();
        if (present(e.getAdresse())) c.add(e.getAdresse().trim());
        if (present(e.getTelephone())) c.add("Tél. " + e.getTelephone().trim());
        if (present(e.getEmail())) c.add(e.getEmail().trim().replaceAll("\\s*[·;]\\s*", " · "));
        return c;
    }

    private void lignesCentrees(List<String> contacts, float premiereBase, float[] couleur) throws IOException {
        List<String> lignes = couper(String.join("   •   ", contacts), REG, 7.5f, w - 2 * marge);
        float y = premiereBase;
        for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
            texteCentre(l, REG, 7.5f, y, couleur);
            y += 10;
        }
    }

    // =====================================================================================
    // Logo
    // =====================================================================================

    private PDImageXObject image() {
        if (!logoCharge) {
            logoCharge = true;
            if (theme.logo() != null) {
                try {
                    logo = PDImageXObject.createFromByteArray(doc, theme.logo(), "logo");
                } catch (Exception e) {
                    logo = null;
                }
            }
        }
        return logo;
    }

    private float largeurLogo(float hauteur) {
        PDImageXObject img = image();
        if (img == null) return hauteur;
        return Math.min(hauteur * img.getWidth() / (float) img.getHeight(), hauteur * 3);
    }

    /** Logo de hauteur {@code hauteur}, coin haut-gauche en (x, haut) ; pastille à l'initiale sans logo. Renvoie son bord droit. */
    private float logo(float x, float haut, float hauteur) throws IOException {
        float largeur = largeurLogo(hauteur);
        if (image() != null) {
            cs.drawImage(image(), x, h - haut - hauteur, largeur, hauteur);
        } else {
            rect(x, haut, x + hauteur, haut + hauteur, theme.couleur());
            String initiale = nom().isEmpty() ? "?" : nom().substring(0, 1).toUpperCase(Locale.FRENCH);
            float t = hauteur * 0.55f;
            texte(initiale, BOLD, t, x + (hauteur - largeur(initiale, BOLD, t)) / 2, haut + hauteur / 2 + t * 0.36f, BLANC);
        }
        return x + largeur;
    }

    /** Logo centré dans une boîte (x, haut, largeur, hauteur). */
    private void logoDans(float x, float haut, float largeurBoite, float hauteurBoite) throws IOException {
        PDImageXObject img = image();
        if (img == null) {
            logo(x, haut, hauteurBoite);
            return;
        }
        float ratio = img.getWidth() / (float) img.getHeight();
        float lw = Math.min(largeurBoite, hauteurBoite * ratio);
        float lh = lw / ratio;
        cs.drawImage(img, x + (largeurBoite - lw) / 2, h - haut - (hauteurBoite + lh) / 2, lw, lh);
    }

    // =====================================================================================
    // Primitives (repère haut-gauche)
    // =====================================================================================

    private void rect(float x1, float y1, float x2, float y2, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(x1, h - y2, x2 - x1, y2 - y1);
        cs.fill();
    }

    private void cadre(float x1, float y1, float x2, float y2, float[] fond, float[] bord, float epaisseur) throws IOException {
        rect(x1, y1, x2, y2, fond);
        cs.setStrokingColor(bord[0], bord[1], bord[2]);
        cs.setLineWidth(epaisseur);
        cs.addRect(x1, h - y2, x2 - x1, y2 - y1);
        cs.stroke();
    }

    private void polygoneDegrade(float[][] points, float ax, float ay, float bx, float by, float[] c0, float[] c1) throws IOException {
        cs.saveGraphicsState();
        cs.moveTo(points[0][0], h - points[0][1]);
        for (int i = 1; i < points.length; i++) cs.lineTo(points[i][0], h - points[i][1]);
        cs.closePath();
        cs.clip();
        cs.shadingFill(degrade(ax, h - ay, bx, h - by, c0, c1));
        cs.restoreGraphicsState();
    }

    private static PDShadingType2 degrade(float x0, float y0, float x1, float y1, float[] c0, float[] c1) {
        COSDictionary fonction = new COSDictionary();
        fonction.setInt(COSName.FUNCTION_TYPE, 2);
        fonction.setItem(COSName.DOMAIN, cos(0f, 1f));
        fonction.setItem(COSName.C0, cos(c0));
        fonction.setItem(COSName.C1, cos(c1));
        fonction.setInt(COSName.N, 1);
        COSDictionary dict = new COSDictionary();
        dict.setInt(COSName.SHADING_TYPE, 2);
        PDShadingType2 s = new PDShadingType2(dict);
        s.setColorSpace(PDDeviceRGB.INSTANCE);
        s.setFunction(new PDFunctionType2(fonction));
        s.setCoords(cos(x0, y0, x1, y1));
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        s.setExtend(extend);
        return s;
    }

    private static COSArray cos(float... v) {
        COSArray a = new COSArray();
        for (float f : v) a.add(new COSFloat(f));
        return a;
    }

    private void texte(String s, PDFont f, float taille, float x, float baseline, float[] rgb) throws IOException {
        String t = propre(s, f);
        if (t.isEmpty()) return;
        cs.beginText();
        cs.setFont(f, taille);
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.newLineAtOffset(x, h - baseline);
        cs.showText(t);
        cs.endText();
    }

    private void texteDroite(String s, PDFont f, float taille, float xDroite, float baseline, float[] rgb) throws IOException {
        texte(s, f, taille, xDroite - largeur(s, f, taille), baseline, rgb);
    }

    private void texteCentre(String s, PDFont f, float taille, float baseline, float[] rgb) throws IOException {
        texte(s, f, taille, (w - largeur(s, f, taille)) / 2, baseline, rgb);
    }

    private void libelleValeur(String libelle, String valeur, float taille, float x, float baseline, float[] cLibelle, float[] cValeur) throws IOException {
        String prefixe = libelle + " : ";
        texte(prefixe, BOLD, taille, x, baseline, cLibelle);
        texte(valeur, REG, taille, x + largeur(prefixe, BOLD, taille), baseline, cValeur);
    }

    public static float largeur(String s, PDFont f, float taille) throws IOException {
        return f.getStringWidth(propre(s, f)) / 1000 * taille;
    }

    private static String tronquer(String s, PDFont f, float taille, float max) throws IOException {
        if (largeur(s, f, taille) <= max) return s;
        String t = s;
        while (t.length() > 1 && largeur(t + "…", f, taille) > max) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    private static List<String> couper(String s, PDFont f, float taille, float max) throws IOException {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : s.split("\\s+")) {
            String essai = courante.isEmpty() ? mot : courante + " " + mot;
            if (largeur(essai, f, taille) > max && !courante.isEmpty()) {
                lignes.add(courante.toString());
                courante = new StringBuilder(mot);
            } else {
                courante = new StringBuilder(essai);
            }
        }
        if (!courante.isEmpty()) lignes.add(courante.toString());
        return lignes;
    }

    /** Remplace les caractères que la police standard (WinAnsi) ne sait pas encoder, pour ne jamais faire échouer le PDF. */
    public static String propre(String s, PDFont f) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').toCharArray()) {
            try {
                f.encode(String.valueOf(c));
                b.append(c);
            } catch (Exception e) {
                b.append('?');
            }
        }
        return b.toString();
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}

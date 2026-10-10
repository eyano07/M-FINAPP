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
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dessine le papier à en-tête (en-tête et pied de page) d'une page PDF selon le modèle et la couleur du
 * thème ({@link ThemeDocument}) : même habillage pour tous les documents générés par le serveur. Le corps
 * du document reste à la charge de l'appelant, entre {@link #entete} (qui renvoie la hauteur occupée en haut
 * de page) et {@link #pied} (hauteur occupée en bas).
 *
 * <p>Une instance par document : le logo et les polices n'y sont intégrés qu'une fois, quel que soit le
 * nombre de pages. Coordonnées internes en repère « haut-gauche » (y croissant vers le bas, comme en CSS),
 * converties vers le repère bas-gauche de PDFBox au moment du dessin.</p>
 *
 * <p>Le modèle CLASSIQUE garde son dessin d'origine (Helvetica). Les cinq autres modèles partagent un même
 * langage visuel : police Inter (embarquée, voir {@code resources/pdf/fonts}), étiquettes en petites
 * capitales espacées, aplats et dégradés de la couleur du thème, formes arrondies, ombres douces et
 * pictogrammes vectoriels pour les coordonnées. Si la police est introuvable, Helvetica la remplace.</p>
 */
public final class EnteteDocumentRendu {

    private static final Logger log = LoggerFactory.getLogger(EnteteDocumentRendu.class);

    public static final PDFont REG = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    public static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    public static final PDFont ITAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final float[] BLANC = {1, 1, 1};
    private static final float[] NOIR = {0.07f, 0.09f, 0.15f};
    private static final float[] GRIS = {0.42f, 0.45f, 0.5f};
    private static final float[] GRIS_FONCE = {0.25f, 0.27f, 0.31f};
    private static final float[] FILET = {0.86f, 0.87f, 0.89f};
    private static final Locale FR = Locale.FRENCH;
    /** Constante de Bézier d'un quart de cercle. */
    private static final float K = 0.5523f;

    /** Graisses de la police des modèles modernes (Inter), avec leur repli Helvetica. */
    private enum Poids {
        NORMAL("Inter-Regular.ttf", REG), MOYEN("Inter-Medium.ttf", REG), SEMI("Inter-SemiBold.ttf", BOLD), GRAS("Inter-Bold.ttf", BOLD);

        final String fichier;
        final PDFont repli;

        Poids(String fichier, PDFont repli) {
            this.fichier = fichier;
            this.repli = repli;
        }
    }

    private enum Icone { ADRESSE, TELEPHONE, EMAIL }

    @FunctionalInterface
    private interface Dessin {
        void faire() throws IOException;
    }

    private final PDDocument doc;
    private final ThemeDocument theme;
    private final float marge;
    private final Map<Poids, PDFont> polices = new EnumMap<>(Poids.class);
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

    // ---------------------------------------------------------------------------------
    // BANDEAU : aplat en dégradé de la couleur du thème, cercles translucides, logo sur tuile blanche
    // ---------------------------------------------------------------------------------

    private float enteteBandeau() throws IOException {
        final float bande = 100;
        boolean clair = luminance(theme.couleur()) > 0.42f;
        float[] encre = clair ? NOIR : BLANC;
        float[] doux = melange(encre, theme.couleur(), 0.28f);
        float[] discret = melange(encre, theme.couleur(), 0.42f);
        rectDegrade(0, 0, w, bande, 0, bande, w, 0,
            clair ? theme.couleur() : theme.fonce(), clair ? theme.melange(BLANC, 0.22f) : theme.couleur());
        cs.saveGraphicsState();
        cs.addRect(0, h - bande, w, bande);
        cs.clip();
        avecAlpha(clair ? 0.12f : 0.09f, () -> cercle(w - 46, 4, 124, encre));
        avecAlpha(clair ? 0.1f : 0.07f, () -> cercle(w - 168, bande + 34, 76, encre));
        cs.restoreGraphicsState();

        float taille = 54;
        float xTexte = tuileLogo(marge, (bande - taille) / 2, taille, 13, clair ? BLANC : BLANC, theme.couleur()) + 18;
        float largeurIds = largeurBlocIds(Poids.SEMI, 5.8f, Poids.MOYEN, 7.8f);
        float xIds = w - marge - largeurIds;
        if (largeurIds > 0) {
            avecAlpha(0.35f, () -> ligne(xIds - 15, 28, xIds - 15, bande - 28, encre, 0.7f));
            blocIds(w - marge, bande / 2, Poids.SEMI, 5.8f, discret, Poids.MOYEN, 7.8f, encre, 14);
        }
        float dispo = (largeurIds > 0 ? xIds - 30 : w - marge) - xTexte;
        blocNomModerne(xTexte, bande / 2, dispo, 19f, 0.8f, encre, doux);
        return bande + 16;
    }

    // ---------------------------------------------------------------------------------
    // ÉPURÉ : blanc, un trait d'accent, texte noir, identifiants en colonne
    // ---------------------------------------------------------------------------------

    private float enteteEpure() throws IOException {
        rectArrondi(marge, 24, marge + 28, 27.4f, 1.7f, theme.couleur());
        float hauteurLogo = 38;
        float xTexte = logoSimple(marge, 40, hauteurLogo) + 15;
        float largeurIds = largeurBlocIds(Poids.SEMI, 5.8f, Poids.NORMAL, 7.8f);
        float yCentre = 40 + hauteurLogo / 2;
        if (largeurIds > 0) {
            blocIds(w - marge, yCentre, Poids.SEMI, 5.8f, theme.fonce(), Poids.NORMAL, 7.8f, GRIS_FONCE, 13);
        }
        float dispo = (largeurIds > 0 ? w - marge - largeurIds - 26 : w - marge) - xTexte;
        blocNomModerne(xTexte, yCentre, dispo, 16f, 0.35f, NOIR, GRIS);
        rect(marge, 96, w - marge, 96.6f, FILET);
        return 110;
    }

    // ---------------------------------------------------------------------------------
    // INSTITUTIONNEL : tout centré, nom espacé en capitales, ornement à losange
    // ---------------------------------------------------------------------------------

    private float enteteCentre() throws IOException {
        float hauteurLogo = 42;
        float largeurLogo = largeurLogo(hauteurLogo);
        logoSimple((w - largeurLogo) / 2, 20, hauteurLogo);
        float y = 20 + hauteurLogo + 22;
        List<String> lignesNom = deuxLignes(nom().toUpperCase(FR), police(Poids.SEMI), 14, 2.6f, w - 2 * marge - 40);
        for (int i = 0; i < lignesNom.size(); i++) {
            texteCentreEsp(lignesNom.get(i), police(Poids.SEMI), 14, y, theme.fonce(), 2.6f);
            y += i < lignesNom.size() - 1 ? 18 : 14;
        }
        String sous = sousTitre();
        if (sous != null) {
            texteCentreEsp(tronquer(sous, police(Poids.NORMAL), 8, w - 2 * marge, 0.5f), police(Poids.NORMAL), 8, y, GRIS, 0.5f);
            y += 10;
        }
        y += 11;
        ornement(y);
        y += 16;
        List<String[]> ids = identifiants();
        if (!ids.isEmpty()) {
            float espace = 9;
            float total = 0;
            for (String[] id : ids) total += largeurPaire(id, Poids.SEMI, 5.6f, Poids.NORMAL, 7.6f, 5);
            total += (ids.size() - 1) * (espace * 2 + 0.7f);
            float x = (w - total) / 2;
            for (int i = 0; i < ids.size(); i++) {
                String[] id = ids.get(i);
                texteEsp(id[0].toUpperCase(FR), police(Poids.SEMI), 5.6f, x, y, theme.fonce(), 0.8f);
                x += largeurEsp(id[0].toUpperCase(FR), police(Poids.SEMI), 5.6f, 0.8f) + 5;
                texte(id[1], police(Poids.NORMAL), 7.6f, x, y, GRIS_FONCE);
                x += largeur(id[1], police(Poids.NORMAL), 7.6f);
                if (i < ids.size() - 1) {
                    ligne(x + espace, y - 6.5f, x + espace, y + 1.5f, FILET, 0.8f);
                    x += espace * 2 + 0.7f;
                }
            }
            y += 6;
        }
        return y + 10;
    }

    // ---------------------------------------------------------------------------------
    // LATÉRAL : rail de couleur sur le bord gauche (nom en vertical), en-tête sobre
    // ---------------------------------------------------------------------------------

    private float enteteLateral() throws IOException {
        float hauteurLogo = 40;
        float xTexte = logoSimple(marge, 26, hauteurLogo) + 15;
        float yCentre = 26 + hauteurLogo / 2;
        float largeurIds = largeurBlocIds(Poids.SEMI, 5.8f, Poids.NORMAL, 7.8f);
        if (largeurIds > 0) {
            float x = w - marge - largeurIds;
            float hauteurBloc = (identifiants().size() - 1) * 13 + 14;
            rectArrondi(x - 13, yCentre - hauteurBloc / 2, x - 10.8f, yCentre + hauteurBloc / 2, 1.1f, theme.couleur());
            blocIds(w - marge, yCentre, Poids.SEMI, 5.8f, theme.fonce(), Poids.NORMAL, 7.8f, GRIS_FONCE, 13);
        }
        float dispo = (largeurIds > 0 ? w - marge - largeurIds - 30 : w - marge) - xTexte;
        blocNomModerne(xTexte, yCentre, dispo, 16f, 0.35f, theme.fonce(), GRIS);
        rect(marge, 92, w - marge, 92.6f, FILET);
        rectArrondi(marge, 91.1f, marge + 42, 93.5f, 1.2f, theme.couleur());
        return 108;
    }

    private void barreLaterale() throws IOException {
        float rail = 16;
        rectDegrade(0, 0, rail, h, 0, 0, 0, h, theme.couleur(), theme.fonce());
        rect(rail + 4, 0, rail + 4.8f, h, theme.melange(BLANC, 0.7f));
        String texte = nom().toUpperCase(FR);
        if (texte.isEmpty()) return;
        PDFont f = police(Poids.SEMI);
        texte = tronquer(texte, f, 6.2f, h * 0.55f, 2.4f);
        cs.beginText();
        cs.setFont(f, 6.2f);
        cs.setCharacterSpacing(2.4f);
        cs.setNonStrokingColor(luminance(theme.couleur()) > 0.42f ? NOIR[0] : 1f, luminance(theme.couleur()) > 0.42f ? NOIR[1] : 1f,
            luminance(theme.couleur()) > 0.42f ? NOIR[2] : 1f);
        cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, rail / 2 + 2.3f, 38));
        cs.showText(propre(texte, f));
        cs.endText();
        cs.setCharacterSpacing(0);
    }

    // ---------------------------------------------------------------------------------
    // ENCADRÉ : carte arrondie à l'ombre douce, pastilles pour les identifiants
    // ---------------------------------------------------------------------------------

    private float enteteEncadre() throws IOException {
        float gauche = marge - 12;
        float droite = w - marge + 12;
        float haut = 18;
        float tuile = 46;
        float contenuHaut = haut + 15;
        float xTexte = marge + tuile + 16;
        float dispo = droite - 18 - xTexte;
        float hauteurNom = hauteurBlocNom(dispo, 15.5f, 0.35f);

        // Pastilles d'identifiants : à la ligne si elles ne tiennent pas, jamais tronquées.
        List<Object[]> pastilles = new ArrayList<>();
        float x = xTexte;
        float y = contenuHaut + hauteurNom + 8;
        for (String[] id : identifiants()) {
            float lw = largeurEsp(id[0].toUpperCase(FR), police(Poids.SEMI), 5.4f, 0.7f);
            float vw = largeur(valeurId(id[1], Poids.MOYEN, 7.2f), police(Poids.MOYEN), 7.2f);
            float pastille = lw + 5 + vw + 14;
            if (x > xTexte && x + pastille > droite - 14) {
                x = xTexte;
                y += 14.5f + 5;
            }
            pastilles.add(new Object[] {id, x, y, pastille, lw});
            x += pastille + 6;
        }
        float basContenu = pastilles.isEmpty() ? contenuHaut + Math.max(tuile, hauteurNom) : Math.max(contenuHaut + tuile, y + 14.5f);
        float bas = basContenu + 15;
        carte(gauche, haut, droite, bas, 12);
        tuileLogo(marge, contenuHaut, tuile, 11, BLANC, theme.couleur());
        blocNomModerne(xTexte, contenuHaut + hauteurNom / 2, dispo, 15.5f, 0.35f, theme.fonce(), GRIS);
        for (Object[] p : pastilles) {
            String[] id = (String[]) p[0];
            float px = (float) p[1];
            float py = (float) p[2];
            float pw = (float) p[3];
            rectArrondi(px, py, px + pw, py + 14.5f, 7.25f, theme.melange(BLANC, 0.86f));
            texteEsp(id[0].toUpperCase(FR), police(Poids.SEMI), 5.4f, px + 7, py + 9.6f, theme.fonce(), 0.7f);
            texte(valeurId(id[1], Poids.MOYEN, 7.2f), police(Poids.MOYEN), 7.2f, px + 7 + (float) p[4] + 5, py + 10, theme.fonce());
        }
        return bas + 16;
    }

    // ---------------------------------------------------------------------------------
    // Pages suivantes : en-tête réduit
    // ---------------------------------------------------------------------------------

    private float enteteReduit() throws IOException {
        String nom = nom().toUpperCase(FR);
        switch (theme.modele()) {
            case BANDEAU -> {
                boolean clair = luminance(theme.couleur()) > 0.42f;
                float[] encre = clair ? NOIR : BLANC;
                rectDegrade(0, 0, w, 40, 0, 40, w, 0,
                    clair ? theme.couleur() : theme.fonce(), clair ? theme.melange(BLANC, 0.22f) : theme.couleur());
                float x = tuileLogo(marge, 9, 22, 6, BLANC, theme.couleur()) + 10;
                texteEsp(tronquer(nom, police(Poids.GRAS), 9.5f, w - marge - x, 0.8f), police(Poids.GRAS), 9.5f, x, 24.2f, encre, 0.8f);
                return 56;
            }
            case ENCADRE -> {
                carte(marge - 12, 14, w - marge + 12, 50, 10);
                float x = tuileLogo(marge, 21, 22, 6, BLANC, theme.couleur()) + 10;
                texteEsp(tronquer(nom, police(Poids.GRAS), 9.5f, w - marge - x, 0.6f), police(Poids.GRAS), 9.5f, x, 35.2f, theme.fonce(), 0.6f);
                return 66;
            }
            case CENTRE -> {
                texteCentreEsp(tronquer(nom, police(Poids.SEMI), 9.5f, w - 2 * marge, 2f), police(Poids.SEMI), 9.5f, 28, theme.fonce(), 2f);
                ornement(40);
                return 56;
            }
            case EPURE -> {
                rectArrondi(marge, 18, marge + 18, 20.4f, 1.2f, theme.couleur());
                float x = logoSimple(marge, 26, 20) + 10;
                texteEsp(tronquer(nom, police(Poids.GRAS), 9.5f, w - marge - x, 0.4f), police(Poids.GRAS), 9.5f, x, 40.2f, NOIR, 0.4f);
                rect(marge, 54, w - marge, 54.6f, FILET);
                return 66;
            }
            default -> {
                float x = logoSimple(marge, 20, 20) + 10;
                texteEsp(tronquer(nom, police(Poids.GRAS), 9.5f, w - marge - x, 0.4f), police(Poids.GRAS), 9.5f, x, 34.2f, theme.fonce(), 0.4f);
                rect(marge, 52, w - marge, 52.6f, FILET);
                rectArrondi(marge, 51.1f, marge + 28, 53.5f, 1.2f, theme.couleur());
                return 66;
            }
        }
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

    /** Texte sur deux lignes au plus : ce qui dépasse est regroupé sur la seconde, terminée par des points de suspension. */
    private static List<String> deuxLignes(String texte, PDFont f, float taille, float espacement, float max) throws IOException {
        List<String> lignes = couperEsp(texte, f, taille, espacement, max);
        if (lignes.size() <= 2) return lignes;
        String reste = String.join(" ", lignes.subList(1, lignes.size()));
        return List.of(lignes.get(0), tronquer(reste, f, taille, max, espacement));
    }

    /** Hauteur que prend {@link #blocNomModerne} (1 ou 2 lignes de nom, plus le sous-titre éventuel). */
    private float hauteurBlocNom(float largeurMax, float taille, float espacement) throws IOException {
        List<String> lignes = couperEsp(nom().toUpperCase(FR), police(Poids.GRAS), taille, espacement, Math.max(largeurMax, 90));
        return Math.min(2, lignes.size()) * taille * 1.2f + (sousTitre() != null ? 4 + 8.4f * 1.3f : 0);
    }

    /**
     * Nom (gras espacé, 1 ou 2 lignes) et sous-titre, le tout centré verticalement sur {@code yCentre}.
     * Renvoie le bas du bloc.
     */
    private float blocNomModerne(float x, float yCentre, float largeurMax, float taille, float espacement,
                                 float[] couleurNom, float[] couleurSous) throws IOException {
        PDFont f = police(Poids.GRAS);
        float max = Math.max(largeurMax, 90);
        List<String> lignes = deuxLignes(nom().toUpperCase(FR), f, taille, espacement, max);
        String sous = sousTitre();
        float hauteurLigne = taille * 1.2f;
        float tailleSous = 8.4f;
        float total = lignes.size() * hauteurLigne + (sous != null ? 4 + tailleSous * 1.3f : 0);
        float haut = yCentre - total / 2;
        float y = haut + hauteurLigne * 0.5f + taille * 0.36f;
        for (String l : lignes) {
            texteEsp(l, f, taille, x, y, couleurNom, espacement);
            y += hauteurLigne;
        }
        float bas = haut + lignes.size() * hauteurLigne;
        if (sous != null) {
            float base = bas + 4 + tailleSous * 0.95f;
            texte(tronquer(sous, police(Poids.NORMAL), tailleSous, max), police(Poids.NORMAL), tailleSous, x, base, couleurSous);
            bas = base + 3;
        }
        return bas;
    }

    // =====================================================================================
    // Pied de page
    // =====================================================================================

    /** Dessine le pied de page (coordonnées de l'entreprise). Renvoie la hauteur occupée depuis le bas. */
    public float pied(PDPageContentStream cs, PDRectangle page) throws IOException {
        this.cs = cs;
        this.w = page.getWidth();
        this.h = page.getHeight();
        switch (theme.modele()) {
            case BANDEAU -> {
                rect(marge, h - 50, w - marge, h - 49.4f, FILET);
                rectArrondi(marge, h - 51.2f, marge + 34, h - 48.8f, 1.2f, theme.couleur());
                colonnesContacts(h - 50, h - 14, marge, theme.couleur());
                return 58;
            }
            case EPURE -> {
                rect(marge, h - 46, w - marge, h - 45.4f, FILET);
                contactsCentres(h - 29, 7.2f, GRIS);
                return 52;
            }
            case CENTRE -> {
                ornement(h - 52);
                contactsCentres(h - 34, 7.2f, GRIS_FONCE);
                return 60;
            }
            case LATERAL -> {
                rect(marge, h - 50, w - marge, h - 49.4f, FILET);
                rectArrondi(marge, h - 51.2f, marge + 42, h - 48.8f, 1.2f, theme.couleur());
                colonnesContacts(h - 50, h - 14, marge, theme.couleur());
                return 58;
            }
            case ENCADRE -> {
                if (contacts().isEmpty()) {
                    rect(marge, h - 40, w - marge, h - 39.4f, FILET);
                    return 46;
                }
                carte(marge - 12, h - 60, w - marge + 12, h - 20, 10);
                colonnesContacts(h - 60, h - 20, marge + 6, theme.fonce());
                return 68;
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

    /**
     * Coordonnées en colonnes (adresse, téléphone, e-mail) précédées d'un pictogramme vectoriel, réparties
     * à parts égales entre {@code xDebut} et la marge droite. Centrées verticalement entre {@code haut} et {@code bas}.
     */
    private void colonnesContacts(float haut, float bas, float xDebut, float[] couleurIcone) throws IOException {
        ParametresEntreprise e = theme.entreprise();
        List<Object[]> items = new ArrayList<>();
        if (present(e.getAdresse())) items.add(new Object[] {Icone.ADRESSE, e.getAdresse().trim()});
        if (present(e.getTelephone())) items.add(new Object[] {Icone.TELEPHONE, e.getTelephone().trim()});
        if (present(e.getEmail())) items.add(new Object[] {Icone.EMAIL, e.getEmail().trim().replaceAll("\\s*[·;]\\s*", " · ")});
        if (items.isEmpty()) return;
        float zone = w - marge - xDebut - (xDebut > marge ? 6 : 0);
        PDFont f = police(Poids.NORMAL);
        int n = items.size();

        // Largeur naturelle de chaque colonne (pictogramme + texte sur une ligne, plafonnée) : si l'ensemble tient,
        // les colonnes sont réparties à égale distance ; sinon elles se partagent la place au prorata et le texte passe à la ligne.
        float[] naturel = new float[n];
        float somme = 0;
        for (int i = 0; i < n; i++) {
            naturel[i] = Math.min(largeur((String) items.get(i)[1], f, 7.2f), zone * 0.6f) + 21;
            somme += naturel[i];
        }
        float ecart = 14;
        boolean tient = somme + (n - 1) * ecart <= zone;
        float[] colonne = new float[n];
        // Un numéro de téléphone ne se coupe pas : sa colonne garde sa largeur, les autres se partagent le reste.
        float fixe = 0;
        float variable = 0;
        for (int i = 0; i < n; i++) {
            if (items.get(i)[0] == Icone.TELEPHONE) fixe += naturel[i];
            else variable += naturel[i];
        }
        for (int i = 0; i < n; i++) {
            colonne[i] = tient || items.get(i)[0] == Icone.TELEPHONE ? naturel[i]
                : (zone - (n - 1) * ecart - fixe) * naturel[i] / variable;
        }
        float pas = tient ? (n > 1 ? (zone - somme) / (n - 1) : 0) : ecart;

        float taille = 7.2f;
        List<List<String>> lignes = new ArrayList<>();
        for (int essai = 0; essai < 2; essai++) {
            lignes.clear();
            boolean debord = false;
            for (int i = 0; i < n; i++) {
                List<String> l = couper((String) items.get(i)[1], f, taille, colonne[i] - 21);
                lignes.add(l);
                debord |= l.size() > 2;
            }
            if (!debord || essai == 1) break;
            taille = 6.5f;   // une coordonnée très longue : police réduite, trois lignes au plus
        }
        float interligne = taille + 2.3f;
        float milieu = (haut + bas) / 2f;
        float x = xDebut;
        for (int i = 0; i < n; i++) {
            icone((Icone) items.get(i)[0], x, milieu - 4.2f, 8.4f, couleurIcone);
            List<String> l = lignes.get(i);
            if (l.size() > 3) {
                List<String> coupe = new ArrayList<>(l.subList(0, 2));
                coupe.add(tronquer(String.join(" ", l.subList(2, l.size())), f, taille, colonne[i] - 21));
                l = coupe;
            }
            float y = milieu + taille * 0.36f - (l.size() - 1) * interligne / 2;
            for (String ligne : l) {
                texte(ligne, f, taille, x + 13, y, GRIS_FONCE);
                y += interligne;
            }
            x += colonne[i] + pas;
        }
    }

    /** Coordonnées sur une ligne centrée (deux au plus), séparées par des points médians. */
    private void contactsCentres(float premiereBase, float taille, float[] couleur) throws IOException {
        PDFont f = police(Poids.NORMAL);
        List<String> lignes = couper(String.join("   ·   ", contacts()), f, taille, w - 2 * marge - 20);
        float y = premiereBase;
        for (String l : lignes.subList(0, Math.min(2, lignes.size()))) {
            texteCentreEsp(l, f, taille, y, couleur, 0.15f);
            y += taille + 3;
        }
    }

    /** « Page n / total », centré juste au-dessus du pied de page ; rien pour un document d'une seule page. */
    public void pagination(PDPageContentStream cs, PDRectangle page, float hauteurPied, int numero, int total) throws IOException {
        if (total <= 1) return;
        this.cs = cs;
        this.w = page.getWidth();
        this.h = page.getHeight();
        PDFont f = theme.modele() == ModeleEntete.CLASSIQUE ? REG : police(Poids.MOYEN);
        texteCentre("Page " + numero + " / " + total, f, 7.2f, h - hauteurPied - 4, GRIS);
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

    private List<String> contacts() {
        ParametresEntreprise e = theme.entreprise();
        List<String> c = new ArrayList<>();
        if (present(e.getAdresse())) c.add(e.getAdresse().trim());
        if (present(e.getTelephone())) c.add("Tél. " + e.getTelephone().trim());
        if (present(e.getEmail())) c.add(e.getEmail().trim().replaceAll("\\s*[·;]\\s*", " · "));
        return c;
    }

    /** Largeur du bloc d'identifiants (étiquettes en colonne puis valeurs), 0 s'il n'y en a pas. */
    private float largeurBlocIds(Poids pLib, float tLib, Poids pVal, float tVal) throws IOException {
        List<String[]> ids = identifiants();
        if (ids.isEmpty()) return 0;
        float lib = 0;
        float val = 0;
        for (String[] id : ids) {
            lib = Math.max(lib, largeurEsp(id[0].toUpperCase(FR), police(pLib), tLib, 0.8f));
            val = Math.max(val, largeur(valeurId(id[1], pVal, tVal), police(pVal), tVal));
        }
        return lib + 9 + val;
    }

    /** Valeur d'identifiant tronquée si elle dépasse le tiers de la page (mise en page préservée). */
    private String valeurId(String valeur, Poids p, float taille) throws IOException {
        return tronquer(valeur, police(p), taille, w * 0.3f);
    }

    /**
     * Identifiants alignés à droite sur {@code xDroite}, centrés verticalement sur {@code yCentre} : étiquettes en
     * petites capitales espacées dans une colonne, valeurs alignées en regard.
     */
    private void blocIds(float xDroite, float yCentre, Poids pLib, float tLib, float[] cLib, Poids pVal, float tVal, float[] cVal,
                         float pas) throws IOException {
        List<String[]> ids = identifiants();
        if (ids.isEmpty()) return;
        float lib = 0;
        for (String[] id : ids) lib = Math.max(lib, largeurEsp(id[0].toUpperCase(FR), police(pLib), tLib, 0.8f));
        float x = xDroite - largeurBlocIds(pLib, tLib, pVal, tVal);
        float y = yCentre - (ids.size() - 1) * pas / 2 + tVal * 0.36f;
        for (String[] id : ids) {
            texteEsp(id[0].toUpperCase(FR), police(pLib), tLib, x, y, cLib, 0.8f);
            texte(valeurId(id[1], pVal, tVal), police(pVal), tVal, x + lib + 9, y, cVal);
            y += pas;
        }
    }

    private float largeurPaire(String[] id, Poids pLib, float tLib, Poids pVal, float tVal, float ecart) throws IOException {
        return largeurEsp(id[0].toUpperCase(FR), police(pLib), tLib, 0.8f) + ecart + largeur(id[1], police(pVal), tVal);
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

    /** Logo sans cadre (modèles modernes) : image à sa hauteur, ou monogramme rond à la couleur du thème. Renvoie son bord droit. */
    private float logoSimple(float x, float haut, float hauteur) throws IOException {
        if (image() != null) {
            float largeur = largeurLogo(hauteur);
            cs.drawImage(image(), x, h - haut - hauteur, largeur, hauteur);
            return x + largeur;
        }
        cercle(x + hauteur / 2, haut + hauteur / 2, hauteur / 2, theme.couleur());
        monogramme(x + hauteur / 2, haut + hauteur / 2, hauteur * 0.5f, luminance(theme.couleur()) > 0.42f ? NOIR : BLANC);
        return x + hauteur;
    }

    /**
     * Tuile arrondie ({@code fond}) portant le logo, ou l'initiale de l'entreprise dans {@code couleurInitiale}
     * sans logo. Renvoie le bord droit de la tuile.
     */
    private float tuileLogo(float x, float haut, float taille, float rayon, float[] fond, float[] couleurInitiale) throws IOException {
        rectArrondi(x, haut, x + taille, haut + taille, rayon, fond);
        PDImageXObject img = image();
        if (img != null) {
            float pad = taille * 0.13f;
            float boite = taille - 2 * pad;
            float ratio = img.getWidth() / (float) img.getHeight();
            float lw = Math.min(boite, boite * ratio);
            float lh = lw / ratio;
            if (lh > boite) {
                lh = boite;
                lw = lh * ratio;
            }
            cs.drawImage(img, x + (taille - lw) / 2, h - haut - (taille + lh) / 2, lw, lh);
        } else {
            monogramme(x + taille / 2, haut + taille / 2, taille * 0.52f, couleurInitiale);
        }
        return x + taille;
    }

    private void monogramme(float cx, float cy, float taille, float[] rgb) throws IOException {
        String initiale = nom().isEmpty() ? "?" : nom().substring(0, 1).toUpperCase(FR);
        PDFont f = police(Poids.GRAS);
        texte(initiale, f, taille, cx - largeur(initiale, f, taille) / 2, cy + taille * 0.36f, rgb);
    }

    // =====================================================================================
    // Décors
    // =====================================================================================

    /** Filet orné d'un losange au centre : sépare l'identité du corps du document (modèle institutionnel). */
    private void ornement(float y) throws IOException {
        float centre = w / 2;
        float[] trait = theme.melange(BLANC, 0.55f);
        ligne(marge, y, centre - 11, y, trait, 0.8f);
        ligne(centre + 11, y, w - marge, y, trait, 0.8f);
        losange(centre, y, 3.6f, theme.couleur());
        losange(centre - 8.5f, y, 1.5f, trait);
        losange(centre + 8.5f, y, 1.5f, trait);
    }

    /** Carte arrondie : ombre douce, fond teinté très clair, liseré. */
    private void carte(float x1, float y1, float x2, float y2, float rayon) throws IOException {
        float[][] ombres = {{0.7f, 0.040f}, {2.0f, 0.030f}, {3.4f, 0.020f}};
        for (float[] o : ombres) {
            avecAlpha(o[1], () -> rectArrondi(x1 + 1, y1 + o[0], x2 - 1, y2 + o[0], rayon, new float[] {0.08f, 0.1f, 0.18f}));
        }
        rectArrondi(x1, y1, x2, y2, rayon, theme.melange(BLANC, 0.955f));
        contourArrondi(x1, y1, x2, y2, rayon, theme.melange(BLANC, 0.78f), 0.8f);
    }

    /** Pictogramme vectoriel de 8-9 pt (épingle, téléphone, enveloppe). */
    private void icone(Icone type, float x, float haut, float taille, float[] rgb) throws IOException {
        float u = taille / 8f;
        switch (type) {
            case ADRESSE -> {
                cercle(x + 4 * u, haut + 3.3f * u, 3.1f * u, rgb);
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
                cs.moveTo(x + 1.15f * u, h - (haut + 4.6f * u));
                cs.lineTo(x + 6.85f * u, h - (haut + 4.6f * u));
                cs.lineTo(x + 4 * u, h - (haut + 8.2f * u));
                cs.closePath();
                cs.fill();
                cercle(x + 4 * u, haut + 3.3f * u, 1.15f * u, BLANC);
            }
            case TELEPHONE -> {
                contourArrondi(x + 1.5f * u, haut, x + 6.5f * u, haut + 8 * u, 1.3f * u, rgb, 0.9f);
                ligne(x + 3.2f * u, haut + 1.5f * u, x + 4.8f * u, haut + 1.5f * u, rgb, 0.7f);
                cercle(x + 4 * u, haut + 6.4f * u, 0.6f * u, rgb);
            }
            case EMAIL -> {
                contourArrondi(x, haut + 1.2f * u, x + 8 * u, haut + 6.8f * u, 1.1f * u, rgb, 0.9f);
                cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
                cs.setLineWidth(0.8f);
                cs.moveTo(x + 0.6f * u, h - (haut + 2.2f * u));
                cs.lineTo(x + 4 * u, h - (haut + 4.9f * u));
                cs.lineTo(x + 7.4f * u, h - (haut + 2.2f * u));
                cs.stroke();
            }
            default -> { }
        }
    }

    // =====================================================================================
    // Primitives (repère haut-gauche)
    // =====================================================================================

    private void rect(float x1, float y1, float x2, float y2, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(x1, h - y2, x2 - x1, y2 - y1);
        cs.fill();
    }

    private void rectArrondi(float x1, float y1, float x2, float y2, float rayon, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cheminArrondi(x1, y1, x2, y2, rayon);
        cs.fill();
    }

    private void contourArrondi(float x1, float y1, float x2, float y2, float rayon, float[] rgb, float epaisseur) throws IOException {
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.setLineWidth(epaisseur);
        cheminArrondi(x1, y1, x2, y2, rayon);
        cs.stroke();
    }

    private void cheminArrondi(float x1, float y1, float x2, float y2, float r) throws IOException {
        float rayon = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        float bas = h - y2;
        float haut = h - y1;
        float k = rayon * K;
        cs.moveTo(x1 + rayon, bas);
        cs.lineTo(x2 - rayon, bas);
        cs.curveTo(x2 - rayon + k, bas, x2, bas + rayon - k, x2, bas + rayon);
        cs.lineTo(x2, haut - rayon);
        cs.curveTo(x2, haut - rayon + k, x2 - rayon + k, haut, x2 - rayon, haut);
        cs.lineTo(x1 + rayon, haut);
        cs.curveTo(x1 + rayon - k, haut, x1, haut - rayon + k, x1, haut - rayon);
        cs.lineTo(x1, bas + rayon);
        cs.curveTo(x1, bas + rayon - k, x1 + rayon - k, bas, x1 + rayon, bas);
        cs.closePath();
    }

    private void cheminCercle(float cx, float cy, float r) throws IOException {
        float y = h - cy;
        float k = r * K;
        cs.moveTo(cx + r, y);
        cs.curveTo(cx + r, y + k, cx + k, y + r, cx, y + r);
        cs.curveTo(cx - k, y + r, cx - r, y + k, cx - r, y);
        cs.curveTo(cx - r, y - k, cx - k, y - r, cx, y - r);
        cs.curveTo(cx + k, y - r, cx + r, y - k, cx + r, y);
        cs.closePath();
    }

    private void cercle(float cx, float cy, float r, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cheminCercle(cx, cy, r);
        cs.fill();
    }

    private void cercleContour(float cx, float cy, float r, float[] rgb, float epaisseur) throws IOException {
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.setLineWidth(epaisseur);
        cheminCercle(cx, cy, r);
        cs.stroke();
    }

    private void losange(float cx, float cy, float demi, float[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.moveTo(cx - demi, h - cy);
        cs.lineTo(cx, h - cy + demi);
        cs.lineTo(cx + demi, h - cy);
        cs.lineTo(cx, h - cy - demi);
        cs.closePath();
        cs.fill();
    }

    private void ligne(float x1, float y1, float x2, float y2, float[] rgb, float epaisseur) throws IOException {
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.setLineWidth(epaisseur);
        cs.moveTo(x1, h - y1);
        cs.lineTo(x2, h - y2);
        cs.stroke();
    }

    /** Exécute {@code dessin} avec une opacité constante (translucidité). */
    private void avecAlpha(float alpha, Dessin dessin) throws IOException {
        cs.saveGraphicsState();
        PDExtendedGraphicsState etat = new PDExtendedGraphicsState();
        etat.setNonStrokingAlphaConstant(alpha);
        etat.setStrokingAlphaConstant(alpha);
        cs.setGraphicsStateParameters(etat);
        dessin.faire();
        cs.restoreGraphicsState();
    }

    private void rectDegrade(float x1, float y1, float x2, float y2, float ax, float ay, float bx, float by, float[] c0, float[] c1) throws IOException {
        polygoneDegrade(new float[][] {{x1, y1}, {x2, y1}, {x2, y2}, {x1, y2}}, ax, ay, bx, by, c0, c1);
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

    // =====================================================================================
    // Texte
    // =====================================================================================

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

    /** Texte avec espacement des lettres (en points, ajouté après chaque caractère). */
    private void texteEsp(String s, PDFont f, float taille, float x, float baseline, float[] rgb, float espacement) throws IOException {
        String t = propre(s, f);
        if (t.isEmpty()) return;
        cs.beginText();
        cs.setFont(f, taille);
        cs.setCharacterSpacing(espacement);
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.newLineAtOffset(x, h - baseline);
        cs.showText(t);
        cs.endText();
        cs.setCharacterSpacing(0);
    }

    private void texteCentreEsp(String s, PDFont f, float taille, float baseline, float[] rgb, float espacement) throws IOException {
        float largeur = largeurEsp(s, f, taille, espacement) - espacement;
        texteEsp(s, f, taille, (w - largeur) / 2, baseline, rgb, espacement);
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

    private static float largeurEsp(String s, PDFont f, float taille, float espacement) throws IOException {
        return largeur(s, f, taille) + espacement * propre(s, f).length();
    }

    private static String tronquer(String s, PDFont f, float taille, float max) throws IOException {
        return tronquer(s, f, taille, max, 0);
    }

    private static String tronquer(String s, PDFont f, float taille, float max, float espacement) throws IOException {
        if (largeurEsp(s, f, taille, espacement) <= max) return s;
        String t = s;
        while (t.length() > 1 && largeurEsp(t + "…", f, taille, espacement) > max) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    private static List<String> couper(String s, PDFont f, float taille, float max) throws IOException {
        return couperEsp(s, f, taille, 0, max);
    }

    private static List<String> couperEsp(String s, PDFont f, float taille, float espacement, float max) throws IOException {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : s.split("\\s+")) {
            String essai = courante.isEmpty() ? mot : courante + " " + mot;
            if (largeurEsp(essai, f, taille, espacement) > max && !courante.isEmpty()) {
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

    // =====================================================================================
    // Polices et couleurs
    // =====================================================================================

    /** Police Inter de la graisse demandée, chargée à la première utilisation ; Helvetica si elle est introuvable. */
    private PDFont police(Poids poids) {
        return polices.computeIfAbsent(poids, p -> {
            try (InputStream in = EnteteDocumentRendu.class.getResourceAsStream("/pdf/fonts/" + p.fichier)) {
                if (in == null) {
                    throw new IOException("police absente : " + p.fichier);
                }
                return PDType0Font.load(doc, in, true);
            } catch (IOException | RuntimeException e) {
                log.warn("Police {} indisponible, Helvetica utilisée : {}", p.fichier, e.getMessage());
                return p.repli;
            }
        });
    }

    /** Mélange {@code a} vers {@code b} : 0 = a, 1 = b. */
    private static float[] melange(float[] a, float[] b, float part) {
        return new float[] {a[0] + (b[0] - a[0]) * part, a[1] + (b[1] - a[1]) * part, a[2] + (b[2] - a[2]) * part};
    }

    /** Luminance relative (WCAG) : décide si le texte posé sur la couleur du thème doit être clair ou foncé. */
    private static float luminance(float[] c) {
        return 0.2126f * lineaire(c[0]) + 0.7152f * lineaire(c[1]) + 0.0722f * lineaire(c[2]);
    }

    private static float lineaire(float v) {
        return v <= 0.03928f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}

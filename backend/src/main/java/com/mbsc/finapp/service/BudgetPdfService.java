package com.mbsc.finapp.service;

import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.TotalSuivi;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget au format PDF (A4 paysage) : en-tete de l'entreprise, informations et circuit d'approbation, synthese
 * par section avec graphique prevu / realise, budget par ligne en trimestres et semestres (sous-totaux par
 * nature SYSCOHADA), ventilation mensuelle, depenses et notes hors budget, signatures. Montants en devise de
 * base (USD) au centime : les totaux se verifient a la lecture.
 */
@Service
@RequiredArgsConstructor
public class BudgetPdfService {

    private static final PDFont REG = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont ITAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGE = 28f;
    private static final float LARGEUR = PAGE.getWidth() - 2 * MARGE;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] MOIS = {"Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin", "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc."};
    private static final float[] GRIS_CLAIR = {0.95f, 0.96f, 0.97f};
    private static final float[] ROUGE = {0.75f, 0.1f, 0.1f};
    private static final float[] VERT = {0.05f, 0.5f, 0.2f};
    private static final float[] GRIS = {0.42f, 0.45f, 0.5f};

    private final BudgetService budgetService;
    private final ThemeDocumentService themeService;

    @PreAuthorize(BudgetService.LECTURE)
    @Transactional
    public byte[] genererPdf(Long budgetId) {
        BudgetResponse budget = budgetService.consulter(budgetId);
        SuiviBudgetResponse suivi = budgetService.suivi(budgetId);
        ThemeDocument theme = themeService.theme();
        try (PDDocument doc = new PDDocument()) {
            Rendu r = new Rendu(doc, theme, budget);
            r.nouvellePage();
            r.informations(budget);
            r.synthese(suivi);
            r.graphiques(suivi);
            r.tableauPeriodes(suivi);
            r.tableauMensuel(suivi);
            r.horsBudget(suivi);
            r.signatures(budget);
            r.piedsDePage();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Génération du PDF du budget impossible : " + e.getMessage(), e);
        }
    }

    // =====================================================================

    /** Rendu sequentiel avec pagination automatique (repere PDFBox : origine en bas a gauche). */
    private static final class Rendu {
        private final PDDocument doc;
        private final BudgetResponse budget;
        private final EnteteDocumentRendu papier;
        private final float[] couleur;
        /** Haut de la zone de contenu de la page en cours (sous l'en-tête et le titre du document). */
        private float hautContenu;
        /** Bas de la zone de contenu (au-dessus du pied de page). */
        private float basContenu = 40f;
        private float hauteurPied;
        private final Map<Character, Boolean> glyphes = new HashMap<>();
        private PDPageContentStream cs;
        private float y;

        Rendu(PDDocument doc, ThemeDocument theme, BudgetResponse budget) {
            this.doc = doc;
            this.budget = budget;
            this.papier = new EnteteDocumentRendu(doc, theme, MARGE);
            this.couleur = theme.couleur();
        }

        // ---------------- pages ----------------

        void nouvellePage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PAGE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            entete(doc.getNumberOfPages() == 1);
            y = hautContenu;
        }

        /** Papier à en-tête du thème, puis le titre du document (budget, référence, statut) sous l'en-tête. */
        private void entete(boolean premierePage) throws IOException {
            float hauteurEntete = papier.entete(cs, PAGE, premierePage);
            hauteurPied = papier.pied(cs, PAGE);
            basContenu = hauteurPied + 22f;
            float haut = PAGE.getHeight() - hauteurEntete - 6;
            String titre = "BUDGET PRÉVISIONNEL " + budget.exercice();
            texte(titre, BOLD, 12, MARGE, haut - 12, couleur);
            texteDroite(budget.reference() + (budget.numeroRevision() > 0 ? "  -  révision n° " + budget.numeroRevision() : "")
                + "  -  " + statut(budget.statut().name()), REG, 8, PAGE.getWidth() - MARGE, haut - 6, GRIS);
            texteDroite(budget.intitule(), ITAL, 8, PAGE.getWidth() - MARGE, haut - 16, GRIS);
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.addRect(MARGE, haut - 22, LARGEUR, 1.2f);
            cs.fill();
            hautContenu = haut - 38;
        }

        void piedsDePage() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
            int total = doc.getNumberOfPages();
            String edite = "Édité le " + LocalDate.now().format(JOUR) + "  -  montants en USD (devise de base de la comptabilité)";
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream p = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    PDPageContentStream ancien = cs;
                    cs = p;
                    float base = hauteurPied + 8;
                    texte(budget.reference() + "  -  " + budget.intitule(), REG, 7, MARGE, base, GRIS);
                    texte(edite, REG, 7, MARGE + 300, base, GRIS);
                    texteDroite("Page " + (i + 1) + " / " + total, REG, 7, PAGE.getWidth() - MARGE, base, GRIS);
                    cs = ancien;
                }
            }
        }

        private void place(float hauteur) throws IOException {
            if (y - hauteur < basContenu) {
                nouvellePage();
            }
        }

        // ---------------- sections ----------------

        void informations(BudgetResponse b) throws IOException {
            titreSection("Informations");
            String[][] lignes = {
                {"Intitulé", b.intitule(), "Exercice", String.valueOf(b.exercice())},
                {"Référence", b.reference() + (b.revisionDeReference() != null ? " (révise " + b.revisionDeReference() + ")" : ""),
                    "Statut", statut(b.statut().name())},
                {"Élaboré par", nvl(b.elaboreParNom()) + date(b.dateCreation(), " le "), "Soumis le", date(b.dateSoumission(), "")},
                {"Approuvé par", nvl(b.approuveParNom()) + date(b.dateApprobation(), " le "), "En exécution depuis", date(b.dateExecution(), "")},
            };
            for (String[] l : lignes) {
                texte(l[0], BOLD, 8, MARGE, y, null);
                texte(nvl(l[1]), REG, 8, MARGE + 85, y, null);
                texte(l[2], BOLD, 8, MARGE + 420, y, null);
                texte(nvl(l[3]), REG, 8, MARGE + 520, y, null);
                y -= 12;
            }
            if (present(b.observation())) {
                texte("Observation", BOLD, 8, MARGE, y, null);
                for (String ligne : couper(b.observation(), REG, 8, LARGEUR - 85)) {
                    texte(ligne, REG, 8, MARGE + 85, y, null);
                    y -= 11;
                }
            }
            if (present(b.motifRejet())) {
                texte("Dernier rejet", BOLD, 8, MARGE, y, null);
                texte(b.motifRejet(), ITAL, 8, MARGE + 85, y, null);
                y -= 12;
            }
            y -= 6;
        }

        void synthese(SuiviBudgetResponse s) throws IOException {
            int mois = s.moisEcoules();
            titreSection("Synthèse (USD)  -  exécution au " + s.calculeLe().format(JOUR)
                + (mois > 0 && mois < 12 ? " (" + mois + " mois écoulé" + (mois > 1 ? "s" : "") + ")" : ""));
            float[] l = {150, 54, 54, 54, 54, 58, 58, 64, 64, 64, 60, 38};
            String[] entete = {"Section", "T1", "T2", "T3", "T4", "S1", "S2", "Prévu année", "Prévu à date", "Réalisé à date",
                "Écart à date", "Taux"};
            Tableau t = new Tableau(l, entete, 1);
            for (TotalSuivi tot : s.sections()) {
                boolean recette = "PRODUITS".equals(tot.section());
                ajouterSynthese(t, tot.libelle(), tot, mois, recette, tot.tauxExecution(), false);
            }
            ajouterSynthese(t, "Résultat prévisionnel (produits - charges)", s.resultat(), mois, true, null, true);
            t.dessiner();
            legende("Écart à date = réalisé à date - prévu à date (prévu cumulé jusqu'au mois en cours). En vert : favorable (recettes "
                + "au-delà du prévu, dépenses en deçà) ; en rouge : défavorable. Taux = réalisé de l'exercice / prévu de l'année.");
        }

        /** Ligne de synthese : periodes du prevu, puis prevu et realise cumules a date et leur ecart, colore selon son sens. */
        private void ajouterSynthese(Tableau t, String libelle, TotalSuivi tot, int mois, boolean recette, BigDecimal taux, boolean gras) {
            BigDecimal[] prevu = tot.prevu().toArray(BigDecimal[]::new);
            BigDecimal[] realise = tot.realise().toArray(BigDecimal[]::new);
            BigDecimal[] tr = MoteurBudgetaire.trimestres(prevu);
            BigDecimal[] se = MoteurBudgetaire.semestres(prevu);
            BigDecimal prevuADate = MoteurBudgetaire.cumul(prevu, mois);
            BigDecimal realiseADate = MoteurBudgetaire.cumul(realise, mois);
            BigDecimal ecart = realiseADate.subtract(prevuADate);
            float[][] couleurs = new float[12][];
            couleurs[10] = couleurEcart(ecart, recette);
            t.ligne(new String[] {libelle, montant(tr[0]), montant(tr[1]), montant(tr[2]), montant(tr[3]), montant(se[0]), montant(se[1]),
                montant(MoteurBudgetaire.total(prevu)), montant(prevuADate), montant(realiseADate), montant(ecart), taux(taux)},
                gras, gras ? GRIS_CLAIR : null, couleurs);
        }

        /** Vert si l'ecart est favorable (recette au-dela du prevu, depense en deca), rouge s'il est defavorable. */
        private float[] couleurEcart(BigDecimal ecart, boolean recette) {
            if (ecart.signum() == 0) {
                return null;
            }
            return (ecart.signum() > 0) == recette ? VERT : ROUGE;
        }

        void graphiques(SuiviBudgetResponse s) throws IOException {
            place(150);
            titreSection("Prévu et réalisé par mois");
            float largeur = (LARGEUR - 20) / 2f;
            float bas = y - 120;
            int i = 0;
            for (TotalSuivi tot : s.sections()) {
                if (i == 2) {
                    break;
                }
                graphique(tot.libelle(), tot.prevu(), tot.realise(), MARGE + i * (largeur + 20), bas, largeur, 110);
                i++;
            }
            y = bas - 14;
        }

        private void graphique(String titre, List<BigDecimal> prevu, List<BigDecimal> realise, float x, float bas, float largeur, float hauteur) throws IOException {
            texte(titre, BOLD, 8, x, bas + hauteur + 4, null);
            BigDecimal max = BigDecimal.ONE;
            for (int m = 0; m < 12; m++) {
                max = max.max(prevu.get(m).abs()).max(realise.get(m).abs());
            }
            float zone = hauteur - 16;
            cs.setStrokingColor(0.8f, 0.82f, 0.85f);
            cs.setLineWidth(0.5f);
            cs.moveTo(x, bas + 12);
            cs.lineTo(x + largeur, bas + 12);
            cs.stroke();
            float pas = largeur / 12f;
            for (int m = 0; m < 12; m++) {
                float bx = x + m * pas + 3;
                float hp = prevu.get(m).abs().divide(max, 6, RoundingMode.HALF_UP).floatValue() * zone;
                float hr = realise.get(m).abs().divide(max, 6, RoundingMode.HALF_UP).floatValue() * zone;
                cs.setNonStrokingColor(0.78f, 0.82f, 0.88f);
                cs.addRect(bx, bas + 12, (pas - 6) / 2f, hp);
                cs.fill();
                cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
                cs.addRect(bx + (pas - 6) / 2f, bas + 12, (pas - 6) / 2f, hr);
                cs.fill();
                texte(MOIS[m].substring(0, Math.min(4, MOIS[m].length())), REG, 6, bx, bas + 3, GRIS);
            }
            cs.setNonStrokingColor(0.78f, 0.82f, 0.88f);
            cs.addRect(x + largeur - 120, bas + hauteur + 2, 7, 7);
            cs.fill();
            texte("Prévu", REG, 7, x + largeur - 110, bas + hauteur + 3, null);
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.addRect(x + largeur - 70, bas + hauteur + 2, 7, 7);
            cs.fill();
            texte("Réalisé", REG, 7, x + largeur - 60, bas + hauteur + 3, null);
            texte("max " + montant(max), REG, 6, x, bas + hauteur - 6, GRIS);
        }

        void tableauPeriodes(SuiviBudgetResponse s) throws IOException {
            place(80);
            titreSection("Budget par ligne : trimestres, semestres et exécution (USD)");
            float[] l = {42, 150, 52, 52, 52, 52, 56, 56, 60, 58, 50, 58, 38};
            String[] entete = {"Compte", "Libellé", "T1", "T2", "T3", "T4", "S1", "S2", "Prévu", "Réalisé", "Engagé", "Disponible", "Taux"};
            Tableau t = new Tableau(l, entete);
            for (TotalSuivi section : s.sections()) {
                boolean recette = "PRODUITS".equals(section.section());
                t.titre(section.libelle().toUpperCase(), couleurClaire());
                for (TotalSuivi nature : s.natures()) {
                    if (!nature.section().equals(section.section())) {
                        continue;
                    }
                    for (LigneSuivi ls : s.lignes()) {
                        if (ls.section().equals(section.section()) && ls.nature().equals(nature.code())) {
                            t.ligne(lignePeriodesDetail(ls.compteNumero(), ls.compteLibelle(), ls.prevu(), ls.realiseAnnuel(),
                                ls.engageAnnuel(), ls.disponibleAnnuel(), ls.tauxExecution()), false, null, couleursDisponible(ls.disponibleAnnuel(), recette));
                        }
                    }
                    BigDecimal dispoNature = nature.prevuAnnuel().subtract(nature.realiseAnnuel()).subtract(nature.engageAnnuel());
                    t.ligne(lignePeriodesDetail(nature.code(), "Total " + nature.libelle(), nature.prevu(), nature.realiseAnnuel(),
                        nature.engageAnnuel(), dispoNature, nature.tauxExecution()), true, GRIS_CLAIR, couleursDisponible(dispoNature, recette));
                }
                BigDecimal dispoSection = section.prevuAnnuel().subtract(section.realiseAnnuel()).subtract(section.engageAnnuel());
                t.ligne(lignePeriodesDetail("", "TOTAL " + section.libelle().toUpperCase(), section.prevu(), section.realiseAnnuel(),
                    section.engageAnnuel(), dispoSection, section.tauxExecution()), true, couleurClaire(), couleursDisponible(dispoSection, recette));
            }
            t.dessiner();
            legende("Réalisé : écritures comptabilisées au grand livre. Engagé : notes de frais validées non encore payées. Disponible = "
                + "prévu - réalisé - engagé ; pour les produits, recettes restant à réaliser (négatif = recettes supérieures au prévu).");
        }

        /** Disponible negatif : depassement (rouge) pour une depense, recette superieure au prevu (vert) pour un produit. */
        private float[][] couleursDisponible(BigDecimal disponible, boolean recette) {
            float[][] c = new float[13][];
            if (disponible != null && disponible.signum() < 0) {
                c[11] = recette ? VERT : ROUGE;
            }
            return c;
        }

        void tableauMensuel(SuiviBudgetResponse s) throws IOException {
            place(80);
            titreSection("Ventilation mensuelle du prévu (USD)");
            float[] l = new float[15];
            l[0] = 38;
            l[1] = 118;
            for (int i = 2; i < 14; i++) {
                l[i] = 47;
            }
            l[14] = 62;
            String[] entete = new String[15];
            entete[0] = "Compte";
            entete[1] = "Libellé";
            System.arraycopy(MOIS, 0, entete, 2, 12);
            entete[14] = "Année";
            Tableau t = new Tableau(l, entete);
            t.taille = 6.2f;
            for (TotalSuivi section : s.sections()) {
                for (LigneSuivi ls : s.lignes()) {
                    if (ls.section().equals(section.section())) {
                        t.ligne(ligneMensuelle(ls.compteNumero(), ls.compteLibelle(), ls.prevu()), false, null);
                    }
                }
                t.ligne(ligneMensuelle("", "TOTAL " + section.libelle().toUpperCase(), section.prevu()), true, GRIS_CLAIR);
            }
            t.dessiner();
            y -= 8;
        }

        void horsBudget(SuiviBudgetResponse s) throws IOException {
            if (!s.horsBudget().isEmpty()) {
                place(60);
                titreSection("Dépenses et recettes hors budget (comptes mouvementés non couverts par une ligne)");
                Tableau t = new Tableau(new float[] {60, 330, 120, 120}, new String[] {"Compte", "Libellé", "Section", "Réalisé"}, 3);
                for (SuiviBudgetResponse.CompteHorsBudget h : s.horsBudget()) {
                    t.ligne(new String[] {h.compteNumero(), nvl(h.compteLibelle()), SuiviBudgetaireService.libelleSection(
                        MoteurBudgetaire.Section.valueOf(h.section())), montant(h.realiseAnnuel())}, false, null);
                }
                t.dessiner();
                y -= 8;
            }
            if (!s.notesHorsBudget().isEmpty()) {
                place(60);
                titreSection("Notes de frais hors budget ou en dépassement (justification exigée)");
                Tableau t = new Tableau(new float[] {78, 170, 80, 70, 72, 316},
                    new String[] {"Note", "Objet", "Montant", "Statut", "Contrôle", "Justification"}, 99);
                for (SuiviBudgetResponse.NoteHorsBudget n : s.notesHorsBudget()) {
                    t.ligne(new String[] {n.reference(), nvl(n.objet()), montant(n.montant()) + " " + nvl(n.devise()), statutNote(n.statut()),
                        controle(n.statutBudget()), nvl(n.justification())}, false, null);
                }
                t.dessiner();
                y -= 8;
            }
        }

        void signatures(BudgetResponse b) throws IOException {
            place(90);
            titreSection("Approbations");
            float col = LARGEUR / 2f;
            String[][] blocs = {
                {"Élaboré par (Direction financière)", nvl(b.elaboreParNom()), date(b.dateSoumission(), "Soumis le ")},
                {"Approuvé par (Direction administrative)", nvl(b.approuveParNom()), date(b.dateApprobation(), "Approuvé le ")},
            };
            for (int i = 0; i < blocs.length; i++) {
                float x = MARGE + i * col;
                texte(blocs[i][0], BOLD, 8.5f, x, y, null);
                texte(blocs[i][1], REG, 8.5f, x, y - 12, null);
                texte(blocs[i][2], REG, 7.5f, x, y - 23, GRIS);
                cs.setStrokingColor(0.6f, 0.62f, 0.66f);
                cs.setLineWidth(0.5f);
                cs.moveTo(x, y - 58);
                cs.lineTo(x + col - 40, y - 58);
                cs.stroke();
                texte("Signature et cachet", ITAL, 7, x, y - 67, GRIS);
            }
            y -= 80;
        }

        // ---------------- tableaux ----------------

        /** Tableau pagine : l'en-tete est repete en haut de chaque page. */
        private final class Tableau {
            final float[] largeurs;
            final String[] entete;
            /** Les colonnes a partir de celle-ci contiennent des montants (alignes a droite). */
            final int premiereNumerique;
            final List<Object[]> lignes = new ArrayList<>();
            float taille = 7f;

            Tableau(float[] largeurs, String[] entete) {
                this(largeurs, entete, 2);
            }

            Tableau(float[] largeurs, String[] entete, int premiereNumerique) {
                this.largeurs = largeurs;
                this.entete = entete;
                this.premiereNumerique = premiereNumerique;
            }

            void ligne(String[] cellules, boolean gras, float[] fond) {
                ligne(cellules, gras, fond, null);
            }

            /** {@code couleurs[c]} impose la couleur de la cellule c (sinon : rouge pour un montant negatif). */
            void ligne(String[] cellules, boolean gras, float[] fond, float[][] couleurs) {
                lignes.add(new Object[] {cellules, gras, fond, couleurs, false});
            }

            /** Ligne de titre (section) sur toute la largeur du tableau. */
            void titre(String libelle, float[] fond) {
                lignes.add(new Object[] {new String[] {libelle}, true, fond, null, true});
            }

            void dessiner() throws IOException {
                float h = taille + 5;
                place(h * 3);
                enteteTableau(h);
                for (Object[] l : lignes) {
                    boolean titre = (Boolean) l[4];
                    // Un titre de section garde au moins sa premiere ligne sur la meme page.
                    if (y - h * (titre ? 2 : 1) < basContenu) {
                        nouvellePage();
                        enteteTableau(h);
                    }
                    String[] cellules = (String[]) l[0];
                    boolean gras = (Boolean) l[1];
                    float[] fond = (float[]) l[2];
                    float[][] couleurs = (float[][]) l[3];
                    if (fond != null) {
                        cs.setNonStrokingColor(fond[0], fond[1], fond[2]);
                        cs.addRect(MARGE, y - h + 2, somme(largeurs), h);
                        cs.fill();
                    }
                    float x = MARGE;
                    if (titre) {
                        texte(tronquer(cellules[0], BOLD, taille, somme(largeurs) - 4), BOLD, taille, x + 2, y - h + 5, null);
                    }
                    for (int c = 0; !titre && c < largeurs.length; c++) {
                        String v = c < cellules.length ? nvl(cellules[c]) : "";
                        PDFont f = gras ? BOLD : REG;
                        v = tronquer(v, f, taille, largeurs[c] - 4);
                        float[] rgb = couleurs != null && c < couleurs.length ? couleurs[c] : null;
                        if (c >= premiereNumerique && estNombre(v)) {
                            texteDroite(v, f, taille, x + largeurs[c] - 2, y - h + 5, rgb != null ? rgb : negatif(v) ? ROUGE : null);
                        } else {
                            texte(v, f, taille, x + 2, y - h + 5, rgb);
                        }
                        x += largeurs[c];
                    }
                    cs.setStrokingColor(0.88f, 0.89f, 0.91f);
                    cs.setLineWidth(0.3f);
                    cs.moveTo(MARGE, y - h + 2);
                    cs.lineTo(MARGE + somme(largeurs), y - h + 2);
                    cs.stroke();
                    y -= h;
                }
            }

            private void enteteTableau(float h) throws IOException {
                cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
                cs.addRect(MARGE, y - h + 2, somme(largeurs), h);
                cs.fill();
                float x = MARGE;
                for (int c = 0; c < largeurs.length; c++) {
                    String v = tronquer(entete[c], BOLD, taille, largeurs[c] - 4);
                    if (c >= premiereNumerique) {
                        texteDroite(v, BOLD, taille, x + largeurs[c] - 2, y - h + 5, new float[] {1, 1, 1});
                    } else {
                        texte(v, BOLD, taille, x + 2, y - h + 5, new float[] {1, 1, 1});
                    }
                    x += largeurs[c];
                }
                y -= h;
            }
        }

        // ---------------- lignes ----------------

        private String[] lignePeriodesDetail(String compte, String libelle, List<BigDecimal> prevu, BigDecimal realise,
                                             BigDecimal engage, BigDecimal disponible, BigDecimal taux) {
            BigDecimal[] m = prevu.toArray(BigDecimal[]::new);
            BigDecimal[] t = MoteurBudgetaire.trimestres(m);
            BigDecimal[] s = MoteurBudgetaire.semestres(m);
            return new String[] {compte, libelle, montant(t[0]), montant(t[1]), montant(t[2]), montant(t[3]), montant(s[0]),
                montant(s[1]), montant(MoteurBudgetaire.total(m)), montant(realise), montant(engage), montant(disponible), taux(taux)};
        }

        private String[] ligneMensuelle(String compte, String libelle, List<BigDecimal> prevu) {
            String[] r = new String[15];
            r[0] = compte;
            r[1] = libelle;
            for (int i = 0; i < 12; i++) {
                r[i + 2] = montant(prevu.get(i));
            }
            r[14] = montant(MoteurBudgetaire.total(prevu.toArray(BigDecimal[]::new)));
            return r;
        }

        // ---------------- texte ----------------

        private void titreSection(String titre) throws IOException {
            if (y < hautContenu) {
                y -= 8;   // respiration entre deux sections (sauf en haut de page)
            }
            place(28);
            texte(titre, BOLD, 9.5f, MARGE, y, couleur);
            y -= 14;
        }

        /** Note de lecture sous un tableau. */
        private void legende(String texte) throws IOException {
            y -= 4;
            for (String ligne : couper(texte, ITAL, 6.5f, LARGEUR)) {
                place(9);
                texte(ligne, ITAL, 6.5f, MARGE, y - 4, GRIS);
                y -= 8;
            }
            y -= 4;
        }

        private void texte(String s, PDFont f, float taille, float x, float yy, float[] rgb) throws IOException {
            String v = sur(s);
            if (v.isEmpty()) {
                return;
            }
            cs.beginText();
            if (rgb != null) {
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
            } else {
                cs.setNonStrokingColor(0.07f, 0.09f, 0.15f);
            }
            cs.setFont(f, taille);
            cs.newLineAtOffset(x, yy);
            cs.showText(v);
            cs.endText();
        }

        private void texteDroite(String s, PDFont f, float taille, float xDroite, float yy, float[] rgb) throws IOException {
            String v = sur(s);
            texte(v, f, taille, xDroite - largeur(v, f, taille), yy, rgb);
        }

        private float largeur(String s, PDFont f, float taille) throws IOException {
            return f.getStringWidth(sur(s)) / 1000f * taille;
        }

        private String tronquer(String s, PDFont f, float taille, float max) throws IOException {
            String v = sur(s);
            if (largeur(v, f, taille) <= max) {
                return v;
            }
            while (v.length() > 1 && largeur(v + "...", f, taille) > max) {
                v = v.substring(0, v.length() - 1);
            }
            return v + "...";
        }

        private List<String> couper(String s, PDFont f, float taille, float max) throws IOException {
            List<String> lignes = new ArrayList<>();
            StringBuilder courante = new StringBuilder();
            for (String mot : sur(s).split(" ")) {
                String essai = courante.length() == 0 ? mot : courante + " " + mot;
                if (largeur(essai, f, taille) > max && courante.length() > 0) {
                    lignes.add(courante.toString());
                    courante = new StringBuilder(mot);
                } else {
                    courante = new StringBuilder(essai);
                }
            }
            if (courante.length() > 0) {
                lignes.add(courante.toString());
            }
            return lignes;
        }

        /** Remplace les caracteres absents de la police standard (encodage WinAnsi) : espaces fines, symboles... */
        private String sur(String s) {
            if (s == null) {
                return "";
            }
            String v = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace('\t', ' ')
                .replace("\r", "").replace('\n', ' ');
            StringBuilder b = new StringBuilder(v.length());
            for (char c : v.toCharArray()) {
                b.append(glyphes.computeIfAbsent(c, this::existe) ? c : '?');
            }
            return b.toString();
        }

        private boolean existe(char c) {
            try {
                REG.getStringWidth(String.valueOf(c));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private float[] couleurClaire() {
            return new float[] {0.85f + couleur[0] * 0.15f, 0.85f + couleur[1] * 0.15f, 0.85f + couleur[2] * 0.15f};
        }
    }

    // =====================================================================
    // Formats
    // =====================================================================

    static String montant(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        String signe = r.signum() < 0 ? "-" : "";
        String[] parties = r.abs().toPlainString().split("\\.");
        StringBuilder entier = new StringBuilder(parties[0]);
        for (int i = entier.length() - 3; i > 0; i -= 3) {
            entier.insert(i, ' ');
        }
        return signe + entier + "," + parties[1];
    }

    private static String taux(BigDecimal t) {
        return t == null ? "-" : t.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %";
    }

    private static boolean estNombre(String v) {
        return v.matches("-?[0-9][0-9 ]*(,[0-9]+)?( %)?") || "-".equals(v);
    }

    private static boolean negatif(String v) {
        return v.startsWith("-") && v.length() > 1;
    }

    private static float somme(float[] l) {
        float s = 0;
        for (float v : l) {
            s += v;
        }
        return s;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String date(Instant i, String prefixe) {
        return i == null ? "" : prefixe + LocalDate.ofInstant(i, ZoneId.systemDefault()).format(JOUR);
    }

    static String statut(String s) {
        return switch (s) {
            case "BROUILLON" -> "Brouillon";
            case "SOUMIS" -> "Soumis";
            case "APPROUVE" -> "Approuvé";
            case "REJETE" -> "Rejeté";
            case "EN_EXECUTION" -> "En exécution";
            case "REMPLACE" -> "Remplacé par une révision";
            case "CLOTURE" -> "Clôturé";
            default -> s;
        };
    }

    private static String statutNote(String s) {
        return switch (s == null ? "" : s) {
            case "BROUILLON" -> "Brouillon";
            case "SOUMISE" -> "Soumise";
            case "VERIFIEE_DFIN" -> "Vérifiée DFIN";
            case "VALIDEE_DA" -> "Validée DA";
            case "REJETEE_DA" -> "Rejetée";
            case "TRANSMISE_CAISSE" -> "À payer";
            case "PAYEE" -> "Payée";
            case "ANNULEE" -> "Annulée";
            default -> nvl(s);
        };
    }

    private static String controle(String s) {
        return switch (s == null ? "" : s) {
            case "DEPASSEMENT" -> "Dépassement";
            case "HORS_BUDGET" -> "Hors budget";
            case "SANS_BUDGET" -> "Sans budget";
            default -> nvl(s);
        };
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.Depassement;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.SuiviService;
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
import java.util.Locale;
import java.util.Map;

/**
 * Budget au format PDF (A4 paysage) : en-tete de l'entreprise, informations (periode, circuit d'approbation),
 * synthese par section avec graphique prevu / realise, budget par ligne en trimestres et semestres (ou mois par
 * mois pour une periode plus courte que l'annee), sous-totaux par nature SYSCOHADA, ventilation mensuelle d'un
 * budget annuel, execution par service, depassements, depenses et notes hors budget, signatures. Montants en
 * devise de base (USD) au centime : les totaux se verifient a la lecture.
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
            r.informations(budget, suivi);
            r.synthese(suivi);
            r.depassements(suivi);
            r.graphiques(suivi);
            r.tableauPeriodes(suivi);
            if (budget.duree() == DureeBudget.ANNUEL) {
                r.tableauMensuel(suivi);
            }
            r.services(suivi);
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
            String titre = budget.duree() == null || budget.duree() == DureeBudget.ANNUEL
                ? "BUDGET PRÉVISIONNEL " + budget.exercice()
                : "BUDGET PRÉVISIONNEL  -  " + budget.libellePeriode().toUpperCase(Locale.FRANCE);
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

        void informations(BudgetResponse b, SuiviBudgetResponse s) throws IOException {
            titreSection("Informations");
            long services = s.services().stream().filter(x -> x.serviceId() != null).count();
            String[][] lignes = {
                {"Intitulé", b.intitule(), "Exercice", String.valueOf(b.exercice())},
                {"Période", b.duree().nom() + "  -  " + b.libellePeriode() + " (" + MOIS[b.moisDebut() - 1]
                    + (b.moisFin() == b.moisDebut() ? "" : " à " + MOIS[b.moisFin() - 1]) + ")",
                    "Services", b.parService() ? services + " service" + (services > 1 ? "s" : "") + " (budget consolidé)"
                        : services > 0 ? "Budget non réparti ; " + services + " service(s) avec des dépenses" : "Budget non réparti par service"},
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
                + (mois > 0 && mois < 12 ? " (" + mois + " mois écoulé" + (mois > 1 ? "s" : "") + " de l'exercice)" : ""));
            List<Colonne> cols = colonnesPeriode();
            float[] l = largeurs(new float[] {150}, cols.size(), 332f, new float[] {64, 64, 64, 60, 38});
            List<String> entete = new ArrayList<>(List.of("Section"));
            cols.forEach(c -> entete.add(c.libelle()));
            entete.addAll(List.of("Prévu " + periodeCourte(), "Prévu à date", "Réalisé à date", "Écart à date", "Taux"));
            Tableau t = new Tableau(l, entete.toArray(String[]::new), 1);
            for (TotalSuivi tot : s.sections()) {
                boolean recette = "PRODUITS".equals(tot.section());
                ajouterSynthese(t, tot.libelle(), tot, mois, recette, tot.tauxExecution(), false, cols);
            }
            ajouterSynthese(t, "Résultat prévisionnel (produits - charges)", s.resultat(), mois, true, null, true, cols);
            t.dessiner();
            legende("Écart à date = réalisé à date - prévu à date (prévu cumulé jusqu'au mois en cours). En vert : favorable (recettes "
                + "au-delà du prévu, dépenses en deçà) ; en rouge : défavorable. Taux = réalisé de la période / prévu de la période"
                + (budget.duree() == DureeBudget.ANNUEL ? "" : " (« " + budget.libellePeriode() + " » : seuls ses mois sont pris en compte)") + ".");
        }

        /** Ligne de synthese : periodes du prevu, puis prevu et realise cumules a date et leur ecart, colore selon son sens. */
        private void ajouterSynthese(Tableau t, String libelle, TotalSuivi tot, int mois, boolean recette, BigDecimal taux, boolean gras,
                                     List<Colonne> cols) {
            BigDecimal[] prevu = tot.prevu().toArray(BigDecimal[]::new);
            BigDecimal[] realise = tot.realise().toArray(BigDecimal[]::new);
            BigDecimal prevuADate = MoteurBudgetaire.cumul(prevu, mois);
            BigDecimal realiseADate = MoteurBudgetaire.cumul(realise, mois);
            BigDecimal ecart = realiseADate.subtract(prevuADate);
            List<String> cellules = new ArrayList<>(List.of(libelle));
            cols.forEach(c -> cellules.add(montant(somme(tot.prevu(), c))));
            cellules.addAll(List.of(montant(MoteurBudgetaire.total(prevu)), montant(prevuADate), montant(realiseADate), montant(ecart),
                taux(taux)));
            float[][] couleurs = new float[cellules.size()][];
            couleurs[cellules.size() - 2] = couleurEcart(ecart, recette);
            t.ligne(cellules.toArray(String[]::new), gras, gras ? GRIS_CLAIR : null, couleurs);
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
            for (int m = budget.moisDebut() - 1; m < budget.moisFin(); m++) {
                max = max.max(prevu.get(m).abs()).max(realise.get(m).abs());
            }
            float zone = hauteur - 16;
            cs.setStrokingColor(0.8f, 0.82f, 0.85f);
            cs.setLineWidth(0.5f);
            cs.moveTo(x, bas + 12);
            cs.lineTo(x + largeur, bas + 12);
            cs.stroke();
            int debut = budget.moisDebut() - 1;
            int nombre = budget.moisFin() - budget.moisDebut() + 1;
            float pas = largeur / (float) nombre;
            for (int m = debut; m < debut + nombre; m++) {
                float bx = x + (m - debut) * pas + 3;
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
            List<Colonne> cols = colonnesPeriode();
            titreSection(budget.duree() == DureeBudget.ANNUEL ? "Budget par ligne : trimestres, semestres et exécution (USD)"
                : cols.isEmpty() ? "Budget par ligne et exécution (USD)" : "Budget par ligne : mois de la période et exécution (USD)");
            float[] l = largeurs(new float[] {42, 150}, cols.size(), 320f, new float[] {60, 58, 50, 58, 38});
            List<String> entete = new ArrayList<>(List.of("Compte", "Libellé"));
            cols.forEach(c -> entete.add(c.libelle()));
            entete.addAll(List.of("Prévu", "Réalisé", "Engagé", "Disponible", "Taux"));
            Tableau t = new Tableau(l, entete.toArray(String[]::new));
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
                                ls.engageAnnuel(), ls.disponibleAnnuel(), ls.tauxExecution(), cols), false, null,
                                couleursDisponible(ls.disponibleAnnuel(), recette, cols));
                        }
                    }
                    BigDecimal dispoNature = nature.prevuAnnuel().subtract(nature.realiseAnnuel()).subtract(nature.engageAnnuel());
                    t.ligne(lignePeriodesDetail(nature.code(), "Total " + nature.libelle(), nature.prevu(), nature.realiseAnnuel(),
                        nature.engageAnnuel(), dispoNature, nature.tauxExecution(), cols), true, GRIS_CLAIR,
                        couleursDisponible(dispoNature, recette, cols));
                }
                BigDecimal dispoSection = section.prevuAnnuel().subtract(section.realiseAnnuel()).subtract(section.engageAnnuel());
                t.ligne(lignePeriodesDetail("", "TOTAL " + section.libelle().toUpperCase(), section.prevu(), section.realiseAnnuel(),
                    section.engageAnnuel(), dispoSection, section.tauxExecution(), cols), true, couleurClaire(),
                    couleursDisponible(dispoSection, recette, cols));
            }
            t.dessiner();
            legende("Réalisé : écritures comptabilisées au grand livre. Engagé : notes de frais validées non encore payées. Disponible = "
                + "prévu - réalisé - engagé ; pour les produits, recettes restant à réaliser (négatif = recettes supérieures au prévu).");
        }

        /** Disponible negatif : depassement (rouge) pour une depense, recette superieure au prevu (vert) pour un produit. */
        private float[][] couleursDisponible(BigDecimal disponible, boolean recette, List<Colonne> cols) {
            int nombre = 2 + cols.size() + 5;
            float[][] c = new float[nombre][];
            if (disponible != null && disponible.signum() < 0) {
                c[nombre - 2] = recette ? VERT : ROUGE;
            }
            return c;
        }

        /** Colonnes de detail : trimestres et semestres d'un budget annuel, mois d'une periode plus courte (aucune pour un mois). */
        private List<Colonne> colonnesPeriode() {
            if (budget.duree() == null || budget.duree() == DureeBudget.ANNUEL) {
                return List.of(new Colonne("T1", 1, 3), new Colonne("T2", 4, 6), new Colonne("T3", 7, 9), new Colonne("T4", 10, 12),
                    new Colonne("S1", 1, 6), new Colonne("S2", 7, 12));
            }
            List<Colonne> cols = new ArrayList<>();
            if (budget.moisFin() > budget.moisDebut()) {
                for (int m = budget.moisDebut(); m <= budget.moisFin(); m++) {
                    cols.add(new Colonne(MOIS[m - 1], m, m));
                }
            }
            return cols;
        }

        /** « année », « semestre », « trimestre » ou « mois ». */
        private String periodeCourte() {
            return switch (budget.duree() == null ? DureeBudget.ANNUEL : budget.duree()) {
                case ANNUEL -> "année";
                case SEMESTRIEL -> "semestre";
                case TRIMESTRIEL -> "trimestre";
                case MENSUEL -> "mois";
            };
        }

        /**
         * Largeurs d'un tableau : colonnes fixes de tete, {@code n} colonnes de periode se partageant {@code espace}, puis
         * colonnes fixes de queue. Sans colonne de periode, l'espace revient a la derniere colonne de tete (le libelle).
         */
        private float[] largeurs(float[] tete, int n, float espace, float[] queue) {
            float[] l = new float[tete.length + n + queue.length];
            System.arraycopy(tete, 0, l, 0, tete.length);
            if (n == 0) {
                l[tete.length - 1] += espace;
            }
            for (int i = 0; i < n; i++) {
                l[tete.length + i] = espace / n;
            }
            System.arraycopy(queue, 0, l, tete.length + n, queue.length);
            return l;
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
                titreSection("Dépenses et recettes hors budget (comptes mouvementés ou engagés non couverts par une ligne)");
                Tableau t = new Tableau(new float[] {60, 330, 120, 120, 120}, new String[] {"Compte", "Libellé", "Section", "Réalisé", "Engagé"}, 3);
                for (SuiviBudgetResponse.CompteHorsBudget h : s.horsBudget()) {
                    t.ligne(new String[] {h.compteNumero(), nvl(h.compteLibelle()), SuiviBudgetaireService.libelleSection(
                        MoteurBudgetaire.Section.valueOf(h.section())), montant(h.realiseAnnuel()), montant(h.engageAnnuel())}, false, null);
                }
                t.dessiner();
                y -= 8;
            }
            if (!s.notesHorsBudget().isEmpty()) {
                place(60);
                titreSection("Notes de frais hors budget ou en dépassement (justification exigée)");
                Tableau t = new Tableau(new float[] {78, 150, 80, 65, 70, 95, 248},
                    new String[] {"Note", "Objet", "Montant", "Statut", "Contrôle", "Service", "Justification"}, 99);
                for (SuiviBudgetResponse.NoteHorsBudget n : s.notesHorsBudget()) {
                    t.ligne(new String[] {n.reference(), nvl(n.objet()), montant(n.montant()) + " " + nvl(n.devise()), statutNote(n.statut()),
                        controle(n.statutBudget()), nvl(n.serviceNom()), nvl(n.justification())}, false, null);
                }
                t.dessiner();
                y -= 8;
            }
            if (!s.nonRattache().isEmpty()) {
                place(60);
                titreSection("Dépenses non rattachées à un service (comptes budgétés seulement par service)");
                Tableau t = new Tableau(new float[] {60, 450, 120, 120}, new String[] {"Compte", "Libellé", "Section", "Réalisé"}, 3);
                for (SuiviBudgetResponse.CompteHorsBudget h : s.nonRattache()) {
                    t.ligne(new String[] {h.compteNumero(), nvl(h.compteLibelle()), SuiviBudgetaireService.libelleSection(
                        MoteurBudgetaire.Section.valueOf(h.section())), montant(h.realiseAnnuel())}, false, null);
                }
                t.dessiner();
                legende("Ces dépenses comptent dans le budget consolidé, mais dans l'enveloppe d'aucun service : écritures passées hors note "
                    + "de frais, ou notes de frais sans service. Rattachez les notes à leur service pour les suivre sur son budget.");
            }
        }

        /** Execution de l'enveloppe de chaque service sur la periode. */
        void services(SuiviBudgetResponse s) throws IOException {
            if (s.services().isEmpty()) {
                return;
            }
            place(80);
            titreSection("Exécution par service (dépenses, USD)");
            Tableau t = new Tableau(new float[] {150, 130, 72, 72, 66, 72, 42, 72, 80, 26},
                new String[] {"Service", "Responsable", "Prévu", "Réalisé", "Engagé", "Disponible", "Taux", "Non budgété", "Dépassement", ""}, 2);
            for (SuiviService sv : s.services()) {
                float[][] c = new float[10][];
                if (sv.disponible().signum() < 0) {
                    c[5] = ROUGE;
                }
                if (sv.depassement().signum() > 0) {
                    c[8] = ROUGE;
                }
                t.ligne(new String[] {sv.nom() + (sv.actif() ? "" : " (désactivé)"), nvl(sv.responsable()), montant(sv.prevu()),
                    montant(sv.realise()), montant(sv.engage()), montant(sv.disponible()), taux(sv.tauxExecution()),
                    montant(sv.nonBudgete()), montant(sv.depassement()), ""}, sv.serviceId() == null, sv.serviceId() == null ? GRIS_CLAIR : null, c);
            }
            t.dessiner();
            legende("Le réalisé et l'engagé d'un service proviennent de ses notes de frais (payées : grand livre ; approuvées non payées : "
                + "engagé). Une dépense d'un service sur un compte qu'il n'a pas budgété s'impute sur la ligne générale du compte si elle "
                + "existe, sinon elle est « non budgétée ». Dépassement = dépassements de ses lignes + dépenses non budgétées.");
        }

        /** Depassements de la periode : services puis budget consolide. */
        void depassements(SuiviBudgetResponse s) throws IOException {
            if (s.depassements().isEmpty()) {
                return;
            }
            place(70);
            titreSection("Dépassements budgétaires de la période (USD)");
            Tableau t = new Tableau(new float[] {170, 52, 210, 70, 70, 70, 66, 78},
                new String[] {"Enveloppe", "Compte", "Libellé", "Nature", "Prévu", "Réalisé", "Engagé", "Dépassement"}, 4);
            for (Depassement d : s.depassements()) {
                float[][] c = new float[8][];
                c[7] = ROUGE;
                String enveloppe = "CONSOLIDE".equals(d.niveau()) ? "Budget consolidé"
                    : d.serviceId() == null ? "Lignes générales" : "Service " + d.serviceNom()
                        + (present(d.responsable()) ? " (" + d.responsable() + ")" : "");
                t.ligne(new String[] {enveloppe, d.compteNumero(), nvl(d.compteLibelle()),
                    "NON_BUDGETE".equals(d.nature()) ? "Non budgété" : "Dépassement", montant(d.prevu()), montant(d.realise()),
                    montant(d.engage()), montant(d.montant())}, false, null, c);
            }
            t.dessiner();
            legende("Dépassement = réalisé + engagé - prévu sur la période, pour une dépense. « Non budgété » : dépense d'un service sur "
                + "un compte qu'aucune de ses lignes (ni ligne générale) ne couvre.");
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
                                             BigDecimal engage, BigDecimal disponible, BigDecimal taux, List<Colonne> cols) {
            List<String> cellules = new ArrayList<>(List.of(compte, libelle));
            cols.forEach(c -> cellules.add(montant(somme(prevu, c))));
            cellules.addAll(List.of(montant(MoteurBudgetaire.total(prevu.toArray(BigDecimal[]::new))), montant(realise), montant(engage),
                montant(disponible), taux(taux)));
            return cellules.toArray(String[]::new);
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

    /** Colonne de detail d'un tableau : un libelle et les mois (1-12) qu'elle totalise. */
    private record Colonne(String libelle, int debut, int fin) {
    }

    private static BigDecimal somme(List<BigDecimal> mensuel, Colonne c) {
        BigDecimal s = BigDecimal.ZERO;
        for (int m = c.debut(); m <= c.fin(); m++) {
            s = s.add(mensuel.get(m - 1));
        }
        return s;
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

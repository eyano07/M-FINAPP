package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.parametrage.InformationsLiasse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Liasse OHADA du classeur des états financiers : page de garde, fiches R1 à R4,
 * BILAN paysage et notes annexes 1 à 36, sur le modèle de la liasse remplie à la
 * main par l'entreprise.
 *
 * <p>La mise en page (bandeaux, couleurs, cadres, largeurs, fusions, intitulés)
 * vient telle quelle du modèle {@code modeles/liasse-ohada.xlsx} ; cette classe
 * n'écrit que les valeurs. Chaque montant vient du Grand Livre ; chaque total,
 * variation ou sous-total est une formule Excel, écrite ici même lorsque le modèle
 * en portait déjà une — certaines formules du classeur d'origine étaient fausses
 * (total net égal à la seule dépréciation, sous-total décalé d'une ligne…).</p>
 *
 * <p>Les notes que le système ne peut pas alimenter (effectifs par catégorie,
 * engagements de retraite, production en quantités…) restent vierges, comme dans
 * le modèle, et sont cochées « N/A » dans la fiche R4.</p>
 */
final class LiasseOhada {

    static final String MODELE = "/modeles/liasse-ohada.xlsx";
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Soldes et mouvements du Grand Livre par préfixe de compte ; un préfixe précédé de « - » est exclu. */
    interface Soldes {
        /** Solde net débiteur (débit − crédit) à la clôture, ou à l'ouverture si {@code ouverture}. type null = tous. */
        BigDecimal solde(TypeCompte type, boolean ouverture, String... prefixes);
        /** Total des débits de l'exercice. */
        BigDecimal debits(String... prefixes);
        /** Total des crédits de l'exercice. */
        BigDecimal credits(String... prefixes);
        /** Mouvement net débiteur de l'exercice, ou de l'exercice précédent si {@code precedent}. */
        BigDecimal flux(boolean precedent, String... prefixes);
        /** true si aucun compte n'a de solde ni de mouvement (exercice sans écriture). */
        boolean vide();
    }

    /**
     * Données de la liasse.
     *
     * @param anterieur soldes de l'exercice décalé de k années (k = 1 à 4), pour la note 31
     */
    record Contexte(LocalDate du, LocalDate au, ParametresEntreprise entreprise, InformationsLiasse infos,
                    Soldes soldes, IntFunction<Soldes> anterieur) {}

    /** Ligne d'un poste : montant de l'exercice et de l'exercice précédent. */
    record V(BigDecimal n, BigDecimal n1) {
        static final V ZERO = new V(BigDecimal.ZERO, BigDecimal.ZERO);
        V plus(V o) { return new V(n.add(o.n), n1.add(o.n1)); }
        V moins(V o) { return new V(n.subtract(o.n), n1.subtract(o.n1)); }
        V oppose() { return new V(n.negate(), n1.negate()); }
    }

    /** Notes toujours applicables : texte réglementaire ou tableaux de synthèse. */
    private static final Set<String> TOUJOURS_APPLICABLES = Set.of("NOTE 2", "NOTE 31", "NOTE 34", "NOTE 36");

    private final XSSFWorkbook wb;
    private final Contexte ctx;
    private final Soldes s;
    /** Ligne (base 0) de chaque code de référence dans les feuilles ACTIF, PASSIF, RESULTAT et TFT de l'application. */
    private final Map<String, Map<String, Integer>> refs = new HashMap<>();
    private final Map<String, Feuille> feuilles = new LinkedHashMap<>();

    private LiasseOhada(XSSFWorkbook wb, Contexte ctx) {
        this.wb = wb;
        this.ctx = ctx;
        this.s = ctx.soldes();
    }

    /** Classeur ouvert sur le modèle de liasse, prêt à recevoir les autres feuilles. */
    static XSSFWorkbook ouvrirModele() {
        try (InputStream in = LiasseOhada.class.getResourceAsStream(MODELE)) {
            if (in == null) {
                throw new IllegalStateException("Modèle de liasse introuvable : " + MODELE);
            }
            return new XSSFWorkbook(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Modèle de liasse illisible", e);
        }
    }

    /**
     * Remplit la liasse. Les feuilles ACTIF, PASSIF, RESULTAT et TFT de
     * l'application doivent déjà exister : le BILAN et la note 34 y renvoient par
     * formule, comme le classeur d'origine.
     */
    static void remplir(XSSFWorkbook wb, Contexte ctx) {
        new LiasseOhada(wb, ctx).remplir();
    }

    private void remplir() {
        for (String f : List.of("ACTIF", "PASSIF", "RESULTAT", "TFT")) {
            refs.put(f, codes(wb.getSheet(f)));
        }
        formulesIndependantes();
        nomsDefinis();
        pageDeGarde();
        ficheR1();
        ficheR2();
        ficheR3();
        bilan();
        notes();
        ficheR4();
    }

    /**
     * Défait les formules « partagées » du modèle (une formule maîtresse recopiée sur
     * une plage). Réécrire une seule case d'un tel groupe laissait l'attribut de
     * partage : le tableur recalculait alors la case d'après la formule maîtresse et
     * non d'après celle qu'on venait d'écrire (audit du 15/11/2026 : colonne N-1 du
     * BILAN et total de la note 13 faux). Chaque case porte désormais sa propre formule.
     */
    private void formulesIndependantes() {
        for (Sheet sh : wb) {
            if (refs.containsKey(sh.getSheetName())) {
                continue;
            }
            Map<Cell, String> formules = new LinkedHashMap<>();
            for (Row row : sh) {
                for (Cell c : row) {
                    if (c.getCellType() == CellType.FORMULA) {
                        formules.put(c, c.getCellFormula());
                    }
                }
            }
            formules.keySet().forEach(Cell::setBlank);
            formules.forEach(Cell::setCellFormula);
        }
    }

    // =====================================================================
    // Identité : noms définis du modèle (_soNom, _ExerClos…)
    // =====================================================================

    private void nomsDefinis() {
        ParametresEntreprise p = ctx.entreprise();
        InformationsLiasse i = ctx.infos();
        String nom = denomination();
        String fin = ctx.au().format(DATE_FR);
        String finPrec = ctx.du().minusDays(1).format(DATE_FR);
        nom("_soNom", nom);
        nom("_nSo", nom);
        nom("_soId", p == null ? "" : nz(p.getNif()));
        nom("_soNumFisc", p == null ? "" : nz(p.getNif()));
        nom("numFisc", p == null ? "" : nz(p.getNif()));
        nom("_regCom", p == null ? "" : nz(p.getRccm()));
        nom("_soAdr", p == null ? "" : nz(p.getAdresse()));
        nom("_sigle", nz(i.sigleUsuel()));
        nom("_soSigle", nz(i.sigleUsuel()));
        nom("_Pays", nz(i.pays()));
        nom("_nCompletPays", nz(i.pays()));
        nom("_centreImpot", nz(i.centreDepot()));
        nom("_Ville", nz(i.ville()));
        nom("_ExerClos", "Exercice clos le " + fin);
        nom("_Duree", "Durée (en mois) " + dureeMois());
        nom("_ExerFin", " " + fin);
        nom("_ExerFin1", " " + finPrec);
        nom("_dDeb", " " + ctx.du().format(DATE_FR));
        nom("_dFin", " " + fin);
        nom("_dAn", " " + ctx.au().getYear());
        nom("_ExerClot", " " + fin);
        nom("_sTabExer", "Tableau financiers au " + fin);
        nombre("_Mois", dureeMois());
        nombre("_nbMois", dureeMois());
    }

    private void nom(String nom, String valeur) {
        XSSFName n = wb.getName(nom);
        if (n == null) {
            n = wb.createName();
            n.setNameName(nom);
        }
        String v = valeur == null || valeur.isEmpty() ? " " : valeur;
        // Une constante de nom défini est limitée à 255 caractères dans Excel.
        if (v.length() > 250) {
            v = v.substring(0, 250);
        }
        n.setRefersToFormula("\"" + v.replace("\"", "\"\"") + "\"");
    }

    private void nombre(String nom, long valeur) {
        XSSFName n = wb.getName(nom);
        if (n != null) {
            n.setRefersToFormula(String.valueOf(valeur));
        }
    }

    private String denomination() {
        ParametresEntreprise p = ctx.entreprise();
        if (p == null) {
            return "Entité";
        }
        return nz(p.getNomComplet()).isBlank() ? nz(p.getNom()) : p.getNomComplet();
    }

    private long dureeMois() {
        return ChronoUnit.MONTHS.between(ctx.du().withDayOfMonth(1), ctx.au().withDayOfMonth(1)) + 1;
    }

    // =====================================================================
    // Page de garde et fiches R1 à R4
    // =====================================================================

    private void pageDeGarde() {
        Feuille f = feuille("PAGE DE GARDE");
        InformationsLiasse i = ctx.infos();
        ParametresEntreprise p = ctx.entreprise();
        f.texte("A", 6, nz(i.pays()).toUpperCase(Locale.FRENCH));
        f.texte("A", 8, "DIRECTION : " + nz(i.directionImpots()));
        f.texte("B", 12, "CENTRE DE DEPOT DE  : " + nz(i.centreDepot()));
        f.texte("E", 30, nz(i.sigleUsuel()));
        f.texte("E", 32, p == null ? "" : adresseComplete(p, i));
    }

    private void ficheR1() {
        Feuille f = feuille("FICHE R1");
        InformationsLiasse i = ctx.infos();
        ParametresEntreprise p = ctx.entreprise();
        f.texte("AI", 6, nz(i.sigleUsuel()));
        f.texte("H", 7, p == null ? "" : adresseComplete(p, i));
        // ZA – exercice ; ZB – arrêté des comptes ; ZC – exercice précédent.
        f.texte("AB", 10, ctx.du().format(DATE_FR));
        f.texte("AN", 10, ctx.au().format(DATE_FR));
        f.texte("W", 12, date(i.dateArreteComptes()));
        LocalDate precedent = i.exercicePrecedentClos() != null ? i.exercicePrecedentClos() : ctx.du().minusDays(1);
        f.texte("W", 14, precedent.format(DATE_FR));
        f.nombre("AQ", 14, i.dureeExercicePrecedentMois() != null ? i.dureeExercicePrecedentMois() : 12);
        // ZD – greffe, registre du commerce, répertoire des entités.
        f.texte("E", 16, nz(i.greffe()));
        f.texte("J", 16, p == null ? "" : nz(p.getRccm()));
        f.texte("AB", 16, nz(i.numeroRepertoireEntites()));
        // ZE – caisse sociale, code importateur, code activité.
        f.texte("G", 19, nz(i.numeroCaisseSociale()));
        f.texte("P", 19, nz(i.codeImportateur()));
        f.texte("AJ", 19, nz(i.codeActivitePrincipale()));
        // ZF – désignation et sigle.
        f.texte("G", 22, denomination());
        f.texte("AL", 22, nz(i.sigleUsuel()));
        // ZG – téléphone, courriel, code, boîte postale, ville. La case du
        // courriel du modèle (4 colonnes étroites) est élargie à la case voisine,
        // restée sans intitulé, pour qu'une adresse entière y tienne.
        f.refusionner(24, "Q", "T", "Q", "Z");
        f.refusionner(24, "W", "Z", null, null);
        f.texte("G", 25, p == null ? "" : nz(p.getTelephone()));
        f.texte("Q", 25, p == null ? "" : nz(p.getEmail()));
        f.texte("AA", 25, nz(i.codePostal()));
        f.texte("AG", 25, nz(i.boitePostale()));
        f.texte("AN", 25, nz(i.ville()));
        // ZH – adresse ; ZI – activité principale.
        f.texte("G", 28, p == null ? "" : adresseComplete(p, i));
        f.texte("G", 30, nz(i.activitePrincipale()));
        f.texte("G", 34, nz(i.personneContact()));
        f.texte("G", 37, nz(i.professionnelComptable()));
        f.texte("G", 44, nz(i.visaExpertComptable()));
        if (i.etatsApprouvesAg() != null) {
            f.texte(i.etatsApprouvesAg() ? "AP" : "AM", 48, "X");
        }
        f.texte("G", 53, nz(i.nomSignataire()));
        f.texte("G", 56, nz(i.qualiteSignataire()));
        f.texte("G", 59, date(i.dateSignature()));
        List<InformationsLiasse.Domiciliation> banques = liste(i.domiciliations()).stream()
            .filter(d -> d != null && !(nz(d.banque()).isBlank() && nz(d.numeroCompte()).isBlank()))
            .limit(10).toList();
        for (int k = 0; k < banques.size(); k++) {
            int ligne = 55 + k;
            f.fusionner(ligne - 1, "AB", "AJ");
            f.fusionner(ligne - 1, "AK", "AQ");
            f.texte("AB", ligne, nz(banques.get(k).banque()));
            f.texte("AK", ligne, nz(banques.get(k).numeroCompte()));
        }
    }

    private void ficheR2() {
        Feuille f = feuille("FICHE R2");
        InformationsLiasse i = ctx.infos();
        // Le modèle portait « =_SoNom » en texte et une part de 100 % en dur sur la première activité.
        f.formule("J", 5, "_soNom");
        f.vider(30, 40, "Y", "AH");
        f.chiffres(10, nz(i.formeJuridique()), "R", "S");
        String regime = nz(i.regimeFiscal());
        f.chiffres(12, regime.length() == 1 ? "0" + regime : regime, "R", "S");
        f.chiffres(14, nz(i.paysSiege()), "R", "S");
        f.chiffres(16, deuxChiffres(i.nbEtablissementsPays()), "R", "S");
        f.chiffres(18, deuxChiffres(i.nbEtablissementsHorsPays()), "R", "S");
        f.chiffres(21, i.premiereAnneeExercice() == null ? "" : String.valueOf(i.premiereAnneeExercice()), "R", "S", "T", "U");
        String controle = nz(i.controle());
        f.texte("AJ", 10, controle.equals("PUBLIC") ? "X" : "");
        f.texte("AJ", 12, controle.equals("PRIVE_NATIONAL") ? "X" : "");
        f.texte("AJ", 14, controle.equals("PRIVE_ETRANGER") ? "X" : "");

        // Activités : le chiffre d'affaires de l'exercice est réparti selon la part
        // saisie ; le reste va à « Divers ».
        BigDecimal ca = chiffreAffaires(s).n();
        List<InformationsLiasse.Activite> activites = new ArrayList<>(liste(i.activites()).stream()
            .filter(a -> a != null && !nz(a.designation()).isBlank()).limit(3).toList());
        if (activites.isEmpty() && !nz(i.activitePrincipale()).isBlank()) {
            activites.add(new InformationsLiasse.Activite(i.activitePrincipale(), i.codeActivitePrincipale(), null));
        }
        int[] lignes = {30, 34, 37};
        BigDecimal parts = BigDecimal.ZERO;
        for (int k = 0; k < activites.size(); k++) {
            InformationsLiasse.Activite a = activites.get(k);
            BigDecimal pct = a.pourcentage() != null ? a.pourcentage()
                : activites.size() == 1 ? BigDecimal.valueOf(100) : null;
            f.texte("B", lignes[k], a.designation());
            String code = nz(a.codeNomenclature()).replace(" ", "");
            if (code.length() == 5) {
                f.chiffres(lignes[k], code, "R", "S", "T", "U", "V");
            } else {
                f.texte("R", lignes[k], code);
            }
            if (pct != null) {
                parts = parts.add(pct);
                f.montant("Y", lignes[k], ca.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                f.montant("AH", lignes[k], pct);
            }
        }
        BigDecimal resteDivers = BigDecimal.valueOf(100).subtract(parts).max(BigDecimal.ZERO);
        if (resteDivers.signum() > 0) {
            f.montant("Y", 40, ca.multiply(resteDivers).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            f.montant("AH", 40, resteDivers);
        }
        f.formule("Y", 42, "SUM(Y30:Y40)");
        f.formule("AH", 42, "SUM(AH30:AH40)");
    }

    private void ficheR3() {
        Feuille f = feuille("FICHE R3");
        InformationsLiasse i = ctx.infos();
        List<InformationsLiasse.Dirigeant> dirigeants = liste(i.dirigeants()).stream()
            .filter(d -> d != null && !(nz(d.nom()) + nz(d.prenoms())).isBlank()).limit(6).toList();
        for (int k = 0; k < dirigeants.size(); k++) {
            InformationsLiasse.Dirigeant d = dirigeants.get(k);
            int l = 12 + k;
            f.texte("B", l, nz(d.nom()));
            f.texte("C", l, nz(d.prenoms()));
            f.texte("D", l, nz(d.qualite()));
            f.texte("E", l, nz(d.nif()));
            f.texte("F", l, nz(d.adresse()));
        }
        List<InformationsLiasse.Administrateur> membres = liste(i.administrateurs()).stream()
            .filter(d -> d != null && !(nz(d.nom()) + nz(d.prenoms())).isBlank()).limit(7).toList();
        for (int k = 0; k < membres.size(); k++) {
            InformationsLiasse.Administrateur d = membres.get(k);
            int l = 25 + k;
            f.texte("B", l, nz(d.nom()));
            f.texte("C", l, nz(d.prenoms()));
            f.texte("D", l, nz(d.qualite()));
            f.texte("E", l, nz(d.adresse()));
        }
    }

    /** Fiche R4 : une croix « A » pour chaque note renseignée, « N/A » pour les autres. */
    private void ficheR4() {
        Feuille f = feuille("FICHE R4");
        for (int l = 7; l <= 52; l++) {
            String note = f.lire("A", l).trim().replaceAll("\\s+", " ");
            if (!note.startsWith("NOTE")) {
                continue;
            }
            Feuille n = feuilles.get(note.toUpperCase(Locale.ROOT).replace("BIS", "bis"));
            boolean applicable = TOUJOURS_APPLICABLES.contains(note) || n != null && n.applicable;
            f.texte("C", l, applicable ? "X" : "");
            f.texte("D", l, applicable ? "" : "X");
            f.centrer("C", l);
            f.centrer("D", l);
        }
    }

    // =====================================================================
    // BILAN paysage : renvois aux feuilles ACTIF et PASSIF de l'application
    // =====================================================================

    private void bilan() {
        Feuille f = feuille("BILAN");
        Map<String, Integer> actif = refs.get("ACTIF");
        Map<String, Integer> passif = refs.get("PASSIF");
        for (int l = 11; l <= 39; l++) {
            String refA = f.lire("A", l).trim();
            // Le modèle code « BC » les créances que la feuille ACTIF code « BG ».
            Integer la = actif.get(refA.equals("BC") ? "BG" : refA);
            if (la != null) {
                int x = la + 1;
                f.formule("D", l, "ACTIF!D" + x);
                f.formule("E", l, "ACTIF!E" + x);
                f.formule("F", l, "D" + l + "-E" + l);
                f.formule("G", l, "ACTIF!G" + x);
            }
            Integer lp = passif.get(f.lire("H", l).trim());
            if (lp != null) {
                f.formule("K", l, "PASSIF!D" + (lp + 1));
                f.formule("L", l, "PASSIF!E" + (lp + 1));
            }
        }
    }

    // =====================================================================
    // Notes annexes
    // =====================================================================

    private void notes() {
        note1();
        note2();
        note3A();
        note3C();
        note3D();
        note4();
        note5();
        note6();
        note7();
        note8();
        note9();
        note10();
        note11();
        note12();
        note13();
        note14();
        note15A();
        note16A();
        note17();
        note18();
        note19();
        note20();
        note21();
        note22();
        note23();
        note24();
        note25();
        note26();
        note27A();
        note28();
        note29();
        note30();
        note31();
        note34();
        for (String vierge : List.of("NOTE 3B", "NOTE 3E", "NOTE 3F", "NOTE 15B", "NOTE 16B", "NOTE 16B bis",
                                     "NOTE 16C", "NOTE 27B", "NOTE 32", "NOTE 33", "NOTE 35", "NOTE 36")) {
            feuille(vierge);
        }
    }

    /** Note 1 : sûretés réelles et engagements hors bilan, non suivis — totaux à zéro par formule. */
    private void note1() {
        Feuille f = feuille("NOTE 1");
        for (String c : List.of("C", "D", "E", "F")) {
            f.formule(c, 17, "SUM(" + c + "13:" + c + "16)");
            f.formule(c, 24, "SUM(" + c + "20:" + c + "23)");
            f.formule(c, 35, "SUM(" + c + "27:" + c + "34)");
            f.formule(c, 36, c + "17+" + c + "24+" + c + "35");
        }
        f.formule("E", 45, "SUM(E38:E44)");
        f.formule("F", 45, "SUM(F38:F44)");
    }

    private void note2() {
        Feuille f = feuille("NOTE 2");
        f.paragraphe("B", 11, 2, "Les états financiers de l'exercice clos le " + ctx.au().format(DATE_FR)
            + " sont établis conformément au Système comptable OHADA (SYSCOHADA révisé) annexé à l'Acte uniforme "
            + "relatif au droit comptable et à l'information financière, selon le Système normal.");
        f.paragraphe("B", 14, 2, "Coût historique. Immobilisations amorties selon le mode linéaire sur leur durée "
            + "d'utilité estimée ; stocks évalués au coût moyen pondéré et dépréciés, le cas échéant, à leur valeur "
            + "nette de réalisation ; créances et dettes inscrites à leur valeur nominale ; produits et charges "
            + "rattachés à l'exercice qui les concerne.");
        f.paragraphe("B", 17, 1, "Néant : aucune dérogation aux postulats et conventions comptables.");
        f.paragraphe("B", 20, 1, "Continuité d'exploitation : les comptes sont arrêtés dans la perspective d'une "
            + "poursuite normale de l'activité. Les montants sont exprimés dans la monnaie de tenue de la comptabilité.");
    }

    /** Note 3A : immobilisations brutes, ouverture → clôture. */
    private void note3A() {
        Feuille f = feuille("NOTE 3A");
        String[][] lignes = {
            {"16", "211"}, {"17", "212", "213", "214"}, {"18", "215", "216"}, {"19", "217", "218", "219"},
            {"21", "22"}, {"23", "231", "232", "233", "237", "239"}, {"25", "234", "235", "238"},
            {"26", "24", "-245"}, {"27", "245"}, {"29", "251"}, {"30", "252"}, {"32", "26"}, {"33", "27"},
        };
        for (String[] l : lignes) {
            int r = Integer.parseInt(l[0]);
            String[] p = Arrays.copyOfRange(l, 1, l.length);
            f.montant("B", r, s.solde(null, true, p));
            f.montant("C", r, s.debits(p));
            f.montant("F", r, s.credits(p));
        }
        for (int r : new int[]{16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27, 29, 30, 32, 33}) {
            f.formule("H", r, "B" + r + "+C" + r + "+D" + r + "+E" + r + "-F" + r + "-G" + r);
        }
        for (String c : List.of("B", "C", "D", "E", "F", "G", "H")) {
            f.formule(c, 15, "SUM(" + c + "16:" + c + "19)");
            f.formule(c, 20, "SUM(" + c + "21:" + c + "27)");
            f.formule(c, 28, "SUM(" + c + "29:" + c + "30)");
            f.formule(c, 31, "SUM(" + c + "32:" + c + "33)");
            f.formule(c, 34, c + "15+" + c + "20+" + c + "28+" + c + "31");
        }
    }

    /** Note 3C : amortissements cumulés. */
    private void note3C() {
        Feuille f = feuille("NOTE 3C");
        String[][] lignes = {
            {"15", "2811"}, {"16", "2812", "2813", "2814"}, {"17", "2815", "2816"}, {"18", "2817", "2818", "2819"},
            {"20", "282"}, {"22", "2831", "2832", "2833", "2837", "2839"}, {"24", "2834", "2835", "2838"},
            {"25", "284", "-2845"}, {"26", "2845"},
        };
        for (String[] l : lignes) {
            int r = Integer.parseInt(l[0]);
            String[] p = Arrays.copyOfRange(l, 1, l.length);
            f.montant("B", r, s.solde(null, true, p).negate());
            f.montant("C", r, s.credits(p));
            f.montant("D", r, s.debits(p));
        }
        for (int r = 15; r <= 26; r++) {
            if (r != 19) {
                f.formule("E", r, "B" + r + "+C" + r + "-D" + r);
            }
        }
        for (String c : List.of("B", "C", "D", "E")) {
            f.formule(c, 19, "SUM(" + c + "15:" + c + "18)");
            f.formule(c, 27, "SUM(" + c + "20:" + c + "26)");
            f.formule(c, 29, c + "19+" + c + "27");
        }
    }

    /** Note 3D : cessions — valeur brute et amortissements sortis, prix de cession (comptes 82). */
    private void note3D() {
        Feuille f = feuille("NOTE 3D");
        // Sans cession comptabilisée (comptes 81 et 82), la note reste vierge : un crédit
        // d'immobilisation financière est le plus souvent un prêt remboursé, pas une cession,
        // et l'y porter ferait apparaître une fausse moins-value.
        boolean cessions = s.debits("81").signum() != 0 || s.credits("82").signum() != 0;
        String[][] lignes = !cessions ? new String[0][] : new String[][]{
            {"15", "211", "2811"}, {"16", "212,213,214", "2812,2813,2814"}, {"17", "215,216", "2815,2816"},
            {"18", "217,218,219", "2817,2818,2819"}, {"20", "22", "282"},
            {"21", "231,232,233,237,239", "2831,2832,2833,2837,2839"}, {"22", "234,235,238", "2834,2835,2838"},
            {"23", "24,-245", "284,-2845"}, {"24", "245", "2845"}, {"26", "26", "296"}, {"27", "27", "297"},
        };
        for (String[] l : lignes) {
            int r = Integer.parseInt(l[0]);
            f.montant("C", r, s.credits(l[1].split(",")));
            f.montant("D", r, s.debits(l[2].split(",")));
            f.formule("E", r, "C" + r + "-D" + r);
        }
        f.montant("F", 19, s.flux(false, "821").negate());
        f.montant("F", 25, s.flux(false, "82", "-821", "-826").negate());
        f.montant("F", 28, s.flux(false, "826").negate());
        int[][] groupes = {{19, 15, 18}, {25, 20, 24}, {28, 26, 27}};
        for (int[] g : groupes) {
            f.formule("C", g[0], "SUM(C" + g[1] + ":C" + g[2] + ")");
            f.formule("D", g[0], "SUM(D" + g[1] + ":D" + g[2] + ")");
            f.formule("E", g[0], "C" + g[0] + "-D" + g[0]);
            f.formule("G", g[0], "F" + g[0] + "-E" + g[0]);
        }
        for (String c : List.of("C", "D", "E", "F", "G")) {
            f.formule(c, 30, c + "19+" + c + "25+" + c + "28");
        }
    }

    private void note4() {
        Feuille f = feuille("NOTE 4").colonnes("B", "C", null, "D");
        f.ligne(13, debit("26"));
        f.ligne(14, debit("271", "278", "277"));
        f.ligne(15, debit("272"));
        f.ligne(16, debit("273"));
        f.ligne(17, debit("274"));
        f.ligne(18, debit("275"));
        f.ligne(19, debit("276"));
        f.somme(20, 13, 19);
        f.ligne(21, credit("296"));
        f.ligne(22, credit("297"));
        f.total(23, "#21+#22");
        f.vider(13, 23, "E", "F", "G");
    }

    private void note5() {
        Feuille f = feuille("NOTE 5").colonnes("C", "D", null, "E");
        f.ligne(14, actif("485"));
        f.ligne(15, actif("48", "-485"));
        f.somme(16, 14, 15);
        f.ligne(17, credit("498"));
        f.total(19, "#16-#17");
        f.ligne(27, passif("481"));
        f.ligne(28, passif("482"));
        f.ligne(29, passif("483"));
        f.ligne(30, passif("48", "-481", "-482", "-483"));
        f.somme(31, 27, 30);
    }

    private void note6() {
        Feuille f = feuille("NOTE 6").colonnes("B", "C", null, "D");
        String[] comptes = {"31", "32", "33", "34", "35", "36", "37", "38"};
        for (int k = 0; k < comptes.length; k++) {
            f.ligne(12 + k, debit(comptes[k]));
        }
        f.somme(20, 12, 19);
        f.ligne(22, credit("39"));
        f.total(24, "#20-#22");
    }

    private void note7() {
        Feuille f = feuille("NOTE 7").colonnes("B", "C", null, "D");
        f.ligne(12, actif("41", "-412", "-414", "-415", "-416", "-418"));
        f.ligne(13, actif("412"));
        f.ligne(14, V.ZERO);
        f.ligne(15, V.ZERO);
        f.ligne(16, actif("414"));
        f.ligne(17, actif("415"));
        f.ligne(18, actif("416"));
        f.ligne(19, actif("418"));
        f.somme(20, 12, 19);
        f.ligne(22, credit("491"));
        f.total(24, "#20-#22");
        f.ligne(25, passif("419"));
        f.ligne(26, V.ZERO);
        f.ligne(27, passif("41", "-419"));
        f.somme(28, 25, 27);
        f.vider(12, 28, "E", "F", "G");
    }

    private void note8() {
        Feuille f = feuille("NOTE 8").colonnes("B", "C", null, "D");
        f.ligne(14, actif("42"));
        f.ligne(15, actif("43"));
        f.ligne(16, actif("44"));
        f.ligne(17, actif("45"));
        f.ligne(18, actif("46"));
        f.ligne(19, actif("475"));
        f.ligne(20, actif("47", "-475", "-478"));
        f.ligne(21, actif("185"));
        f.ligne(22, actif("186", "187"));
        f.ligne(23, actif("188"));
        f.somme(24, 14, 23);
        f.ligne(26, credit("49", "-490", "-491", "-498", "-499"));
        f.total(28, "#24-#26");
        f.vider(14, 28, "E", "F", "G");
        f.vider(25, 25, "B", "C", "D");
        f.vider(27, 27, "B", "C", "D");
    }

    private void note9() {
        Feuille f = feuille("NOTE 9").colonnes("B", "C", null, "D");
        String[][] lignes = {{"11", "501"}, {"12", "502"}, {"13", "503"}, {"14", "504"}, {"15", "505"}, {"16", "506"},
                             {"17", "50", "-501", "-502", "-503", "-504", "-505", "-506"}};
        for (String[] l : lignes) {
            f.ligne(Integer.parseInt(l[0]), debit(Arrays.copyOfRange(l, 1, l.length)));
        }
        f.somme(18, 11, 17);
        f.ligne(20, credit("590"));
        f.total(22, "#18-#20");
    }

    private void note10() {
        Feuille f = feuille("NOTE 10").colonnes("B", "C", null, "D");
        String[][] lignes = {{"10", "511"}, {"11", "512"}, {"12", "513"}, {"13", "514"}, {"14", "515"},
                             {"15", "51", "-511", "-512", "-513", "-514", "-515"}};
        for (String[] l : lignes) {
            f.ligne(Integer.parseInt(l[0]), debit(Arrays.copyOfRange(l, 1, l.length)));
        }
        f.somme(16, 10, 15);
        f.ligne(18, credit("591"));
        f.total(20, "#16-#18");
    }

    private void note11() {
        Feuille f = feuille("NOTE 11").colonnes("C", "D", null, "E");
        f.ligne(12, actif("521"));
        f.ligne(13, actif("522"));
        f.ligne(14, actif("525"));
        f.ligne(15, actif("52", "-521", "-522", "-525", "-526"));
        f.ligne(16, actif("526"));
        f.ligne(17, actif("531"));
        f.ligne(18, actif("53", "-531", "-536"));
        f.ligne(19, actif("536"));
        f.ligne(20, actif("54"));
        f.ligne(21, actif("57"));
        f.ligne(22, actif("55"));
        f.ligne(23, actif("58"));
        f.somme(24, 12, 23);
        f.ligne(26, credit("59", "-590", "-591"));
        f.total(28, "#24-#26");
    }

    private void note12() {
        Feuille f = feuille("NOTE 12");
        f.montant("F", 12, s.solde(TypeCompte.ACTIF, false, "478"));
        f.montant("F", 16, s.solde(TypeCompte.PASSIF, false, "479").negate());
        f.colonnes("B", "C", null, "D");
        f.ligne(25, produit("781"));
        f.ligne(29, produit("787"));
    }

    private void note13() {
        Feuille f = feuille("NOTE 13");
        f.montant("F", 23, s.solde(null, false, "109"));
        f.montant("F", 24, s.solde(null, false, "101", "102", "103", "104").negate());
        f.formule("G", 24, "SUM(G15:G22)");
    }

    private void note14() {
        Feuille f = feuille("NOTE 14").colonnes("C", "D", "E", null);
        f.ligne(10, credit("1051"));
        f.ligne(11, credit("1052"));
        f.ligne(12, credit("1053"));
        f.ligne(13, credit("1054"));
        f.ligne(14, credit("105", "-1051", "-1052", "-1053", "-1054"));
        f.somme(15, 10, 14);
        f.ligne(17, credit("111"));
        f.ligne(18, credit("112"));
        f.ligne(19, credit("1131"));
        f.ligne(20, credit("1132"));
        f.ligne(21, credit("113", "-1131", "-1132"));
        f.somme(22, 17, 21);
        f.ligne(24, credit("118"));
        f.ligne(26, credit("12"));
        f.vider(16, 16, "E");
        f.vider(23, 23, "E");
        f.vider(25, 25, "E");
    }

    private void note15A() {
        Feuille f = feuille("NOTE 15A").colonnes("C", "D", "E", "F");
        f.texte("B", 9, "");
        f.formule("C", 9, "_ExerFin");
        String[] subventions = {"1411", "1412", "1413", "1414", "1415", "1416", "1417"};
        for (int k = 0; k < subventions.length; k++) {
            f.ligne(10 + k, credit(subventions[k]));
        }
        f.ligne(17, credit("14", "-1411", "-1412", "-1413", "-1414", "-1415", "-1416", "-1417"));
        f.somme(18, 10, 17);
        f.ligne(20, credit("151"));
        f.ligne(21, credit("152"));
        f.ligne(22, credit("154"));
        f.ligne(23, credit("155"));
        f.ligne(24, credit("156"));
        f.ligne(25, credit("157"));
        f.ligne(26, credit("15", "-151", "-152", "-154", "-155", "-156", "-157"));
        f.somme(27, 20, 26);
        f.total(29, "#18+#27");
        f.vider(9, 38, "B");
        f.vider(19, 19, "C", "D", "E", "F");
        f.vider(28, 28, "C", "D", "E", "F");
        f.vider(31, 38, "C");
    }

    private void note16A() {
        Feuille f = feuille("NOTE 16A").colonnes("B", "C", "D", "E");
        String[] emprunts = {"161", "162", "163", "164", "165", "166", "167", "168"};
        for (int k = 0; k < emprunts.length; k++) {
            f.ligne(7 + k, credit(emprunts[k]));
        }
        f.ligne(15, credit("181", "182", "183"));
        f.ligne(16, credit("184"));
        f.somme(17, 7, 16);
        f.ligne(19, credit("172"));
        f.ligne(20, credit("173"));
        f.ligne(21, credit("174"));
        f.ligne(22, credit("176"));
        f.ligne(23, credit("17", "-172", "-173", "-174", "-176"));
        f.somme(24, 19, 23);
        String[] provisions = {"191", "192", "193", "194", "195", "196"};
        for (int k = 0; k < provisions.length; k++) {
            f.ligne(26 + k, credit(provisions[k]));
        }
        f.ligne(32, V.ZERO);
        f.ligne(33, credit("197"));
        f.ligne(34, V.ZERO);
        f.ligne(35, V.ZERO);
        f.ligne(36, V.ZERO);
        f.ligne(37, V.ZERO);
        f.ligne(38, credit("19", "-191", "-192", "-193", "-194", "-195", "-196", "-197"));
        f.somme(39, 26, 38);
        f.vider(7, 39, "F", "G", "H");
    }

    private void note17() {
        Feuille f = feuille("NOTE 17").colonnes("B", "C", null, "D");
        f.ligne(10, passif("40", "-402", "-408", "-409"));
        f.ligne(11, passif("402"));
        f.ligne(12, V.ZERO);
        f.ligne(13, passif("408"));
        f.ligne(14, V.ZERO);
        f.somme(15, 10, 14);
        f.ligne(17, actif("409"));
        f.ligne(18, V.ZERO);
        f.ligne(19, actif("40", "-409"));
        f.somme(20, 17, 19);
        f.vider(10, 20, "E", "F", "G");
    }

    private void note18() {
        Feuille f = feuille("NOTE 18").colonnes("B", "C", "D", "E");
        f.ligne(11, passif("421"));
        f.ligne(12, passif("422"));
        f.ligne(13, passif("42", "-421", "-422"));
        f.ligne(14, passif("431"));
        f.ligne(15, passif("432"));
        f.ligne(16, passif("43", "-431", "-432"));
        f.somme(17, 11, 16);
        f.ligne(19, passif("441"));
        f.ligne(20, passif("442", "446"));
        f.ligne(21, passif("443", "444", "445"));
        f.ligne(22, passif("447"));
        f.ligne(23, passif("44", "-441", "-442", "-443", "-444", "-445", "-446", "-447"));
        f.somme(24, 19, 23);
        f.total(26, "#17+#24");
        f.vider(11, 26, "F", "G", "H");
        f.vider(18, 18, "B", "C", "D", "E");
        f.vider(25, 25, "E");
    }

    private void note19() {
        Feuille f = feuille("NOTE 19").colonnes("B", "C", "D", "E");
        f.ligne(10, passif("45"));
        f.ligne(11, passif("461"));
        f.ligne(12, passif("462"));
        f.ligne(13, passif("465"));
        f.ligne(14, passif("466"));
        f.ligne(15, passif("46", "-461", "-462", "-465", "-466"));
        f.somme(16, 10, 15);
        f.ligne(17, passif("471"));
        f.ligne(18, passif("467"));
        f.ligne(19, V.ZERO);
        f.ligne(20, V.ZERO);
        f.ligne(21, passif("472"));
        f.ligne(22, passif("475"));
        f.ligne(23, passif("47", "-471", "-472", "-475", "-479"));
        f.somme(24, 17, 23);
        f.ligne(25, passif("185"));
        f.ligne(26, passif("186", "187"));
        f.ligne(27, passif("188"));
        f.somme(28, 25, 27);
        f.total(30, "#16+#24+#28");
        f.ligne(32, credit("499"));
        f.vider(10, 30, "F", "G", "H");
        f.vider(29, 29, "B", "C", "D", "E");
    }

    private void note20() {
        Feuille f = feuille("NOTE 20").colonnes("B", "C", null, "D");
        f.ligne(12, passif("564"));
        f.ligne(13, passif("565"));
        f.total(14, "#12+#13");
        f.vider(16, 16, "B", "C");
        f.ligne(17, passif("521"));
        f.ligne(18, passif("522"));
        f.ligne(19, passif("52", "-521", "-522", "-526"));
        f.ligne(20, passif("526"));
        f.ligne(21, passif("56", "-564", "-565"));
        f.somme(22, 17, 21);
        f.total(24, "#14+#22");
    }

    // ---- Charges et produits ----

    private void note21() {
        Feuille f = feuille("NOTE 21").colonnes("B", "C", null, "D");
        f.ligne(13, produit("701", "-7012", "-7013", "-7014", "-7015"));
        f.ligne(14, produit("7012"));
        f.ligne(15, produit("7013", "7014"));
        f.ligne(16, produit("7015"));
        f.somme(17, 13, 16);
        f.ligne(19, produit("702", "-7022", "-7023", "-7024", "-7025"));
        f.ligne(20, produit("7022"));
        f.ligne(21, produit("7023", "7024"));
        f.ligne(22, produit("7025"));
        f.somme(23, 19, 22);
        f.ligne(25, produit("704", "705", "706", "-7052", "-7053", "-7054", "-7055", "-7062", "-7063", "-7064", "-7065"));
        f.ligne(26, produit("7052", "7062"));
        f.ligne(27, produit("7053", "7054", "7063", "7064"));
        f.ligne(28, produit("7055", "7065"));
        f.somme(29, 25, 28);
        f.ligne(31, produit("707"));
        f.total(33, "#17+#23+#29+#31");
        f.ligne(35, produit("72"));
        f.ligne(36, produit("71"));
        f.ligne(37, produit("75"));
        f.somme(38, 35, 37);
        f.total(40, "#33+#38");
        for (int r : new int[]{18, 24, 34}) {
            f.vider(r, r, "D");
        }
    }

    private void note22() {
        Feuille f = feuille("NOTE 22").colonnes("B", "C", null, "D");
        f.ligne(11, charge("601", "-6012", "-6013", "-6014"));
        f.ligne(12, charge("6012"));
        f.ligne(13, charge("6013", "6014"));
        f.somme(14, 11, 13);
        f.ligne(16, charge("602", "-6022", "-6023", "-6024"));
        f.ligne(17, charge("6022"));
        f.ligne(18, charge("6023", "6024"));
        f.somme(19, 16, 18);
        String[][] autres = {{"21", "6041"}, {"22", "6042"}, {"23", "6043"}, {"24", "6044"}, {"25", "6051"},
                             {"26", "6052"}, {"27", "6053"}, {"28", "6054"}, {"29", "6055"}, {"30", "6056"}};
        for (String[] a : autres) {
            f.ligne(Integer.parseInt(a[0]), charge(a[1]));
        }
        f.ligne(31, charge("604", "605", "-6041", "-6042", "-6043", "-6044", "-6045", "-6049",
            "-6051", "-6052", "-6053", "-6054", "-6055", "-6056", "-6059"));
        f.ligne(32, charge("608", "-6089"));
        f.ligne(33, charge("6045"));
        f.ligne(34, charge("6049", "6059", "6089"));
        f.somme(35, 21, 34);
    }

    private void note23() {
        Feuille f = feuille("NOTE 23").colonnes("B", "C", null, "D");
        f.ligne(10, charge("612"));
        f.ligne(11, charge("613"));
        f.ligne(12, charge("614"));
        f.ligne(13, charge("616"));
        f.ligne(14, charge("61", "-612", "-613", "-614", "-616"));
        f.somme(15, 10, 14);
    }

    private void note24() {
        Feuille f = feuille("NOTE 24").colonnes("B", "C", null, "D");
        String[] comptes = {"621", "622", "623", "624", "625", "626", "627", "628", "631", "632", "633", "634", "635"};
        for (int k = 0; k < comptes.length; k++) {
            f.ligne(10 + k, charge(comptes[k]));
        }
        List<String> reste = new ArrayList<>(List.of("62", "63"));
        for (String c : comptes) {
            reste.add("-" + c);
        }
        f.ligne(23, charge(reste.toArray(String[]::new)));
        f.somme(24, 10, 23);
    }

    private void note25() {
        Feuille f = feuille("NOTE 25").colonnes("B", "C", null, "D");
        f.ligne(11, charge("641"));
        f.ligne(12, charge("645"));
        f.ligne(13, charge("646"));
        f.ligne(14, charge("647"));
        f.ligne(15, charge("64", "-641", "-645", "-646", "-647"));
        f.somme(16, 11, 15);
    }

    private void note26() {
        Feuille f = feuille("NOTE 26").colonnes("B", "C", null, "D");
        f.ligne(10, charge("651"));
        f.ligne(11, V.ZERO);
        f.ligne(12, charge("652"));
        f.ligne(13, charge("654"));
        f.ligne(14, charge("6581"));
        f.ligne(15, charge("6582", "6583"));
        f.ligne(16, charge("65", "-651", "-652", "-654", "-6581", "-6582", "-6583", "-659"));
        f.ligne(17, charge("659"));
        f.somme(19, 10, 17);
        f.vider(18, 18, "D");
    }

    private void note27A() {
        Feuille f = feuille("NOTE 27A").colonnes("B", "C", null, "D");
        f.ligne(11, charge("661", "662"));
        f.ligne(12, charge("663"));
        f.ligne(13, charge("664"));
        f.ligne(14, charge("666"));
        f.ligne(15, charge("667"));
        f.ligne(16, charge("66", "-661", "-662", "-663", "-664", "-666", "-667"));
        f.somme(18, 11, 16);
        f.vider(17, 17, "D");
    }

    /**
     * Note 28 : provisions et dépréciations. Le Grand Livre ne dit pas si une
     * dotation est d'exploitation, financière ou HAO ; les mouvements sont classés
     * selon la nature du poste : HAO pour les provisions réglementées,
     * financier pour les titres de placement, exploitation pour le reste.
     */
    private void note28() {
        Feuille f = feuille("NOTE 28");
        Object[][] lignes = {
            {12, "E", "H", new String[]{"15"}},
            {13, "C", "F", new String[]{"19"}},
            {14, "C", "F", new String[]{"29"}},
            {17, "C", "F", new String[]{"39"}},
            {18, "C", "F", new String[]{"498"}},
            {19, "C", "F", new String[]{"490"}},
            {20, "C", "F", new String[]{"491"}},
            {22, "C", "F", new String[]{"49", "-490", "-491", "-498", "-499"}},
            {23, "D", "G", new String[]{"590"}},
            {24, "C", "F", new String[]{"591"}},
            {25, "C", "F", new String[]{"59", "-590", "-591"}},
            {26, "C", "F", new String[]{"499"}},
        };
        for (int r = 12; r <= 30; r++) {
            f.vider(r, r, "B", "C", "D", "E", "F", "G", "H", "I");
        }
        for (Object[] l : lignes) {
            int r = (Integer) l[0];
            String[] p = (String[]) l[3];
            f.montant("B", r, s.solde(null, true, p).negate());
            f.montant((String) l[1], r, s.credits(p));
            f.montant((String) l[2], r, s.debits(p));
        }
        for (int r : new int[]{12, 13, 14, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27}) {
            f.formule("I", r, "B" + r + "+C" + r + "+D" + r + "+E" + r + "-F" + r + "-G" + r + "-H" + r);
        }
        for (String c : List.of("B", "C", "D", "E", "F", "G", "H", "I")) {
            f.formule(c, 15, "SUM(" + c + "12:" + c + "14)");
            f.formule(c, 28, "SUM(" + c + "17:" + c + "27)");
            f.formule(c, 30, c + "15+" + c + "28");
        }
    }

    private void note29() {
        Feuille f = feuille("NOTE 29").colonnes("B", "C", null, "D");
        String[] frais = {"671", "672", "673", "674", "675", "676", "677"};
        for (int k = 0; k < frais.length; k++) {
            f.ligne(10 + k, charge(frais[k]));
        }
        f.ligne(17, V.ZERO);
        f.ligne(18, charge("67", "-671", "-672", "-673", "-674", "-675", "-676", "-677", "-679"));
        f.ligne(19, charge("679"));
        f.somme(20, 10, 19);
        String[] revenus = {"771", "772", "773", "774", "775", "776", "777"};
        for (int k = 0; k < revenus.length; k++) {
            f.ligne(22 + k, produit(revenus[k]));
        }
        f.ligne(29, produit("77", "-771", "-772", "-773", "-774", "-775", "-776", "-777", "-779"));
        f.ligne(30, produit("779"));
        f.somme(31, 22, 30);
        f.total(33, "#20+#31");
    }

    private void note30() {
        Feuille f = feuille("NOTE 30").colonnes("B", "C", null, "D");
        f.ligne(10, charge("83", "-834", "-835", "-836", "-839"));
        f.ligne(11, charge("834"));
        f.ligne(12, charge("835"));
        f.ligne(13, charge("836"));
        f.ligne(14, charge("839"));
        f.ligne(15, charge("85"));
        f.ligne(16, charge("87"));
        f.ligne(17, V.ZERO);
        f.somme(18, 10, 17);
        f.ligne(20, produit("84", "88", "-845", "-846", "-848", "-849"));
        f.ligne(21, produit("845"));
        f.ligne(22, produit("846"));
        f.ligne(23, produit("848"));
        f.ligne(24, produit("849"));
        f.ligne(25, produit("86"));
        f.somme(26, 20, 25);
        f.total(28, "#18+#26");
    }

    /** Note 31 : cinq derniers exercices (colonnes B = N … F = N-4). */
    private void note31() {
        Feuille f = feuille("NOTE 31");
        String[] colonnes = {"B", "C", "D", "E", "F"};
        for (int k = 0; k < 5; k++) {
            Soldes x = k == 0 ? s : ctx.anterieur().apply(k);
            String c = colonnes[k];
            for (int r = 10; r <= 31; r++) {
                f.vider(r, r, c);
            }
            if (x == null || k > 0 && x.vide()) {
                continue;
            }
            f.montant(c, 11, x.solde(null, false, "101", "102", "103", "104").negate());
            f.formule(c, 10, c + "11");
            f.montant(c, 18, chiffreAffaires(x).n());
            // Résultat des activités ordinaires hors dotations et reprises :
            // EBE + produits financiers encaissables − frais financiers décaissables.
            BigDecimal ebe = x.flux(false, "70", "71", "72", "73", "75", "781", "60", "61", "62", "63", "64", "65", "66").negate();
            BigDecimal financier = x.flux(false, "77", "787", "67", "-779", "-679").negate();
            f.montant(c, 19, ebe.add(financier));
            f.montant(c, 20, x.flux(false, "87"));
            f.montant(c, 21, x.flux(false, "89"));
            f.montant(c, 22, x.flux(false, "6", "7", "8").negate());
            f.montant(c, 29, x.flux(false, "661", "662", "663"));
            f.montant(c, 30, x.flux(false, "664", "668"));
            f.montant(c, 31, x.flux(false, "667"));
        }
    }

    /** Note 34 : fiche de synthèse, par renvois au RESULTAT, au TFT et au BILAN (comme le classeur d'origine). */
    private void note34() {
        Feuille f = feuille("NOTE 34").colonnes("C", "D", null, "E");
        f.texte("A", 6, "   (MONTANTS EN UNITÉS MONÉTAIRES)");
        for (int r = 8; r <= 63; r++) {
            f.vider(r, r, "C", "D", "E", "F");
        }
        String[][] sig = {{"10", "XB"}, {"11", "XA"}, {"12", "XC"}, {"13", "XD"}, {"14", "XE"}, {"15", "XF"},
                          {"16", "XG"}, {"17", "XH"}, {"18", "XI"}};
        for (String[] l : sig) {
            f.total(Integer.parseInt(l[0]), renvoi("RESULTAT", l[1]));
        }
        f.total(20, "#13");
        f.ligne(21, charge("654"));
        f.ligne(22, produit("754"));
        f.total(23, "#20+#21-#22");
        f.ligne(24, produit("77", "-776", "-779"));
        f.ligne(25, produit("776"));
        f.ligne(26, produit("787"));
        f.ligne(27, produit("84", "88", "-848", "-849"));
        f.ligne(28, produit("848"));
        f.ligne(29, charge("67", "-676", "-679"));
        f.ligne(30, charge("676"));
        f.ligne(31, charge("87"));
        f.ligne(32, charge("89"));
        f.total(33, renvoi("TFT", "FA"));
        f.total(34, "ABS(" + renvoi("TFT", "FN") + ")");
        f.total(35, "#33-#34");
        // Le BILAN porte le net N en F (actif) et K (passif), le net N-1 en G et L.
        f.formule("C", 37, "IF((BILAN!K21+BILAN!K25)=0,0," + renvoi("RESULTAT", "XE").replace("§", "E")
            + "/(BILAN!K21+BILAN!K25))");
        f.formule("D", 37, "IF((BILAN!L21+BILAN!L25)=0,0," + renvoi("RESULTAT", "XE").replace("§", "F")
            + "/(BILAN!L21+BILAN!L25))");
        f.formule("C", 38, "IF(BILAN!K21=0,0," + renvoi("RESULTAT", "XI").replace("§", "E") + "/BILAN!K21)");
        f.formule("D", 38, "IF(BILAN!L21=0,0," + renvoi("RESULTAT", "XI").replace("§", "F") + "/BILAN!L21)");
        f.variations(37);
        f.variations(38);
        f.bilan(40, "K21");
        f.bilan(41, "K25");
        f.total(42, "#40+#41");
        f.bilan(43, "F26");
        f.total(44, "#42-#43");
        f.bilan(45, "F28+BILAN!F29");
        f.bilan(46, "K33-BILAN!K27");
        f.total(47, "#45-#46");
        f.bilan(48, "F27");
        f.bilan(49, "K27");
        f.total(50, "#48-#49");
        f.total(51, "#47+#50");
        f.total(53, "#44-#51");
        f.bilan(54, "F37-BILAN!K37");
        f.total(56, renvoi("TFT", "ZB"));
        f.total(57, renvoi("TFT", "ZC"));
        f.total(58, renvoi("TFT", "ZF"));
        f.total(59, "#56+#57+#58");
        f.bilan(61, "K25+BILAN!K37");
        f.bilan(62, "F37");
        f.total(63, "#61-#62");
    }

    /** Renvoi à la ligne d'un code dans une feuille de l'application ; « § » = colonne (E pour N, F pour N-1). */
    private String renvoi(String feuille, String code) {
        Integer l = refs.getOrDefault(feuille, Map.of()).get(code);
        return l == null ? "0" : feuille + "!§" + (l + 1);
    }

    // =====================================================================
    // Montants par préfixe
    // =====================================================================

    private V actif(String... p) {
        return new V(s.solde(TypeCompte.ACTIF, false, p), s.solde(TypeCompte.ACTIF, true, p));
    }

    private V passif(String... p) {
        return new V(s.solde(TypeCompte.PASSIF, false, p).negate(), s.solde(TypeCompte.PASSIF, true, p).negate());
    }

    private V debit(String... p) {
        return new V(s.solde(null, false, p), s.solde(null, true, p));
    }

    private V credit(String... p) {
        return debit(p).oppose();
    }

    private V charge(String... p) {
        return new V(s.flux(false, p), s.flux(true, p));
    }

    private V produit(String... p) {
        return charge(p).oppose();
    }

    /** Chiffre d'affaires au sens du compte de résultat (XB) : 701, 702, 704 à 707. */
    private static V chiffreAffaires(Soldes x) {
        String[] p = {"701", "702", "704", "705", "706", "707"};
        return new V(x.flux(false, p).negate(), x.flux(true, p).negate());
    }

    /** true si le compte relève d'au moins un préfixe positif et d'aucun préfixe exclu (« -… »). */
    static boolean correspond(String numero, String... prefixes) {
        if (numero == null) {
            return false;
        }
        boolean inclus = false;
        for (String p : prefixes) {
            if (p.startsWith("-")) {
                if (numero.startsWith(p.substring(1))) {
                    return false;
                }
            } else if (numero.startsWith(p)) {
                inclus = true;
            }
        }
        return inclus;
    }

    // =====================================================================
    // Outils
    // =====================================================================

    private static Map<String, Integer> codes(Sheet sh) {
        Map<String, Integer> m = new HashMap<>();
        if (sh == null) {
            return m;
        }
        for (Row row : sh) {
            Cell c = row.getCell(0);
            if (c != null && c.getCellType() == CellType.STRING) {
                String v = c.getStringCellValue().trim();
                if (v.matches("[A-Z]{2}")) {
                    m.putIfAbsent(v, row.getRowNum());
                }
            }
        }
        return m;
    }

    private static String adresseComplete(ParametresEntreprise p, InformationsLiasse i) {
        StringJoiner j = new StringJoiner(", ");
        for (String x : new String[]{p.getAdresse(), i.ville(), i.pays()}) {
            if (x != null && !x.isBlank()) {
                j.add(x.trim());
            }
        }
        return j.toString();
    }

    private static String deuxChiffres(Integer n) {
        if (n == null) {
            return "";
        }
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE_FR);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> liste(List<T> l) {
        return l == null ? List.of() : l;
    }

    private Feuille feuille(String nom) {
        return feuilles.computeIfAbsent(nom, n -> {
            Sheet sh = wb.getSheet(n);
            if (sh == null) {
                throw new IllegalStateException("Feuille absente du modèle de liasse : " + n);
            }
            return new Feuille(sh);
        });
    }

    /** Écriture dans une feuille du modèle, en gardant le style de chaque case. */
    private final class Feuille {
        private final Sheet sh;
        /** Au moins un montant non nul écrit : la note est « applicable » (fiche R4). */
        boolean applicable;
        private String colN, colN1, colAbs, colPct;

        Feuille(Sheet sh) {
            this.sh = sh;
        }

        /** Colonnes N, N-1, variation en valeur absolue et en % (null = absente). */
        Feuille colonnes(String n, String n1, String abs, String pct) {
            colN = n;
            colN1 = n1;
            colAbs = abs;
            colPct = pct;
            return this;
        }

        void ligne(int r, V v) {
            montant(colN, r, v.n());
            montant(colN1, r, v.n1());
            variations(r);
        }

        /**
         * Formule sur les colonnes N et N-1 : « # » devient la colonne de la note,
         * « § » celle des feuilles RESULTAT et TFT de l'application (E pour N, F pour N-1).
         */
        void total(int r, String modele) {
            formule(colN, r, modele.replace("#", colN).replace("§", "E"));
            formule(colN1, r, modele.replace("#", colN1).replace("§", "F"));
            variations(r);
        }

        void somme(int r, int de, int a) {
            total(r, "SUM(#" + de + ":#" + a + ")");
        }

        /** Renvoi au BILAN : net N (colonnes F / K) et net N-1 (G / L). */
        void bilan(int r, String expr) {
            formule(colN, r, "BILAN!" + expr);
            formule(colN1, r, "BILAN!" + expr.replace("F", "G").replace("K", "L"));
            variations(r);
        }

        void variations(int r) {
            String n = colN + r, n1 = colN1 + r;
            if (colAbs != null) {
                formule(colAbs, r, n + "-" + n1);
            }
            if (colPct != null) {
                formule(colPct, r, "IF(" + n1 + "=0,0,(" + n + "-" + n1 + ")/ABS(" + n1 + ")*100)");
            }
        }

        void montant(String col, int r, BigDecimal v) {
            BigDecimal x = v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
            if (x.signum() != 0) {
                applicable = true;
            }
            valeur(col, r).setCellValue(x.doubleValue());
        }

        void nombre(String col, int r, long v) {
            valeur(col, r).setCellValue(v);
        }

        /** Case prête à recevoir une valeur : la formule du modèle, s'il y en avait une, est retirée (POI la garderait). */
        private Cell valeur(String col, int r) {
            Cell c = case_(col, r);
            if (c.getCellType() == CellType.FORMULA) {
                c.setBlank();
            }
            return c;
        }

        void formule(String col, int r, String f) {
            case_(col, r).setCellFormula(f);
        }

        void texte(String col, int r, String t) {
            Cell c = valeur(col, r);
            if (t == null || t.isEmpty()) {
                c.setBlank();
            } else {
                c.setCellValue(t);
            }
        }

        /** Un chiffre par case (codes de la fiche R2) ; un code d'une autre longueur va entier dans la première. */
        void chiffres(int r, String code, String... cols) {
            String v = code == null ? "" : code.trim();
            if (v.length() == cols.length) {
                for (int k = 0; k < cols.length; k++) {
                    texte(cols[k], r, String.valueOf(v.charAt(k)));
                    centrer(cols[k], r);
                }
            } else {
                for (int k = 0; k < cols.length; k++) {
                    texte(cols[k], r, k == 0 ? v : "");
                }
            }
        }

        /** Paragraphe dans une zone fusionnée du modèle, renvoyé à la ligne, sur {@code lignes} lignes. */
        void paragraphe(String col, int r, int lignes, String texte) {
            Cell c = case_(col, r);
            c.setCellValue(texte);
            CellStyle st = wb.createCellStyle();
            st.cloneStyleFrom(c.getCellStyle());
            st.setWrapText(true);
            st.setVerticalAlignment(VerticalAlignment.TOP);
            c.setCellStyle(st);
            float hauteur = Math.max(30f, texte.length() / 95f * 15f + 15f) / lignes;
            for (int k = 0; k < lignes; k++) {
                Row row = sh.getRow(r - 1 + k);
                if (row == null) {
                    row = sh.createRow(r - 1 + k);
                }
                row.setHeightInPoints(Math.max(row.getHeightInPoints(), hauteur));
            }
        }

        void centrer(String col, int r) {
            Cell c = case_(col, r);
            if (c.getCellStyle().getAlignment() != HorizontalAlignment.CENTER) {
                CellStyle st = wb.createCellStyle();
                st.cloneStyleFrom(c.getCellStyle());
                st.setAlignment(HorizontalAlignment.CENTER);
                c.setCellStyle(st);
            }
        }

        void vider(int de, int a, String... cols) {
            for (int r = de; r <= a; r++) {
                Row row = sh.getRow(r - 1);
                if (row == null) {
                    continue;
                }
                for (String col : cols) {
                    Cell c = row.getCell(CellReference.convertColStringToIndex(col));
                    if (c != null) {
                        c.setBlank();
                    }
                }
            }
        }

        String lire(String col, int r) {
            Row row = sh.getRow(r - 1);
            Cell c = row == null ? null : row.getCell(CellReference.convertColStringToIndex(col));
            return c != null && c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
        }

        /** Fusionne {@code de}:{@code a} sur la ligne (base 0) si la zone n'est pas déjà fusionnée. */
        void fusionner(int ligne0, String de, String a) {
            int c1 = CellReference.convertColStringToIndex(de), c2 = CellReference.convertColStringToIndex(a);
            for (CellRangeAddress m : sh.getMergedRegions()) {
                if (m.getFirstRow() <= ligne0 && m.getLastRow() >= ligne0 && m.getFirstColumn() <= c2 && m.getLastColumn() >= c1) {
                    return;
                }
            }
            sh.addMergedRegion(new CellRangeAddress(ligne0, ligne0, c1, c2));
        }

        /** Retire la fusion {@code de}:{@code a} de la ligne (base 0) et, si demandé, en pose une autre. */
        void refusionner(int ligne0, String de, String a, String nouveauDe, String nouveauA) {
            int c1 = CellReference.convertColStringToIndex(de), c2 = CellReference.convertColStringToIndex(a);
            for (int k = sh.getNumMergedRegions() - 1; k >= 0; k--) {
                CellRangeAddress m = sh.getMergedRegion(k);
                if (m.getFirstRow() == ligne0 && m.getFirstColumn() == c1 && m.getLastColumn() == c2) {
                    sh.removeMergedRegion(k);
                }
            }
            if (nouveauDe != null) {
                fusionner(ligne0, nouveauDe, nouveauA);
            }
        }

        /** Case (ligne en base 1, comme dans Excel) ; une case absente du modèle reprend le style de celle du dessus. */
        private Cell case_(String col, int r) {
            int c = CellReference.convertColStringToIndex(col);
            Row row = sh.getRow(r - 1);
            if (row == null) {
                row = sh.createRow(r - 1);
            }
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c);
                Row dessus = sh.getRow(r - 2);
                Cell modele = dessus == null ? null : dessus.getCell(c);
                if (modele != null) {
                    cell.setCellStyle(modele.getCellStyle());
                }
            }
            return cell;
        }
    }
}

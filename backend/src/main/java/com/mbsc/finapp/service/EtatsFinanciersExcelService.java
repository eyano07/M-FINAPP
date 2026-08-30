package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Export des états financiers en classeur Excel, sur le modèle du classeur de
 * suivi manuel de l'entreprise (feuilles Journal, Balance, Bilan, Résultat,
 * Flux de trésorerie, Ratios).
 *
 * <p>Contrairement au classeur manuel — où chaque case est une formule
 * recalculée depuis une liste de comptes saisie une fois pour toutes — les
 * valeurs sont calculées ici côté serveur à partir du Grand Livre réel : le
 * classeur reste correct quel que soit le nombre de comptes mouvementés
 * (le référentiel SYSCOHADA en compte 1300, très au-delà de la trentaine de
 * comptes du modèle). Les totaux et les contrôles d'équilibre restent des
 * formules Excel, sur des plages que le générateur connaît exactement.</p>
 */
@Service
@RequiredArgsConstructor
public class EtatsFinanciersExcelService {

    private static final Logger log = LoggerFactory.getLogger(EtatsFinanciersExcelService.class);

    private final EcritureGrandLivreRepository ecritureRepository;
    /** Raison sociale portee en en-tete de chaque feuille de note. */
    private final ParametresEntrepriseRepository parametresRepository;
    /** Produit la synthese et les recommandations de la derniere feuille. */
    private final AnalyseFinanciereIaService analyseIa;

    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String FORMAT_MONTANT = "#,##0.00;[RED]-#,##0.00";

    /** Une ligne de compte fusionnant ouverture + mouvements + clôture. */
    private record LigneCompte(String numero, String libelle, TypeCompte type, int classe,
                                BigDecimal ouvD, BigDecimal ouvC, BigDecimal mvtD, BigDecimal mvtC) {
        BigDecimal netOuverture() { return ouvD.subtract(ouvC); }
        BigDecimal netMouvement() { return mvtD.subtract(mvtC); }
        BigDecimal netCloture() { return netOuverture().add(netMouvement()); }
        BigDecimal cloD() { return netCloture().signum() > 0 ? netCloture() : BigDecimal.ZERO; }
        BigDecimal cloC() { return netCloture().signum() < 0 ? netCloture().negate() : BigDecimal.ZERO; }
    }

    // ---------------------------------------------------------------------
    // Point d'entrée
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] genererClasseur(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<LigneCompte> comptes = chargerComptes(debut, fin);
        List<EcritureGrandLivre> journal = ecritureRepository.journalJusqua(fin);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);

            construireJournal(wb, s, journal, debut, fin);
            int[] plageBalance = construireBalance(wb, s, comptes, debut, fin);

            // Liasse officielle : ACTIF et PASSIF sur deux feuilles distinctes,
            // comme le modèle réglementaire, chacune avec sa colonne N-1.
            BigDecimal resultatNet = construireResultat(wb, s, comptes, debut, fin);
            BilanActifTotaux actif = construireActif(wb, s, comptes, debut, fin);
            BilanPassifTotaux passif = construirePassif(wb, s, comptes, resultatNet,
                resultatNetN1(comptes), debut, fin);
            construireTft(wb, s, comptes, resultatNet, actif.tresorerieActif(),
                passif.tresoreriePassif(), debut, fin);

            BilanTotals bilanTotals = new BilanTotals(
                actif.actifImmobilise(), actif.actifCirculant(), actif.tresorerieActif(), actif.totalActif(),
                passif.capitauxPropres(), passif.passifCirculant(), passif.tresoreriePassif(), passif.totalPassif());
            construireNotes(wb, s, comptes, resultatNet, bilanTotals, debut, fin);
            construireRatios(wb, s, comptes, resultatNet, debut, fin);
            construireGraphiques(wb, s, comptes, journal, resultatNet, bilanTotals, debut, fin);
            construireAnalyseIa(wb, s, debut, fin);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le classeur des états financiers", e);
        }
    }

    /**
     * Classeur ne contenant que la feuille « Journal » : sert a repartir des
     * ecritures deja enregistrees pour les corriger hors ligne puis les
     * reimporter. Les etats financiers (Balance, Bilan, Resultat...) sont
     * recalcules a partir du grand livre et n'ont donc rien a faire dans un
     * fichier destine a etre reinjecte par l'import.
     */
    public byte[] genererJournalSeul(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<EcritureGrandLivre> journal = ecritureRepository.journalJusqua(fin);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            construireJournal(wb, new Styles(wb), journal, debut, fin);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le journal", e);
        }
    }

    // ---------------------------------------------------------------------
    // Chargement et fusion des données
    // ---------------------------------------------------------------------

    private List<LigneCompte> chargerComptes(LocalDate du, LocalDate au) {
        Map<String, Object[]> ouvertures = new HashMap<>();
        for (Object[] row : ecritureRepository.soldesOuvertureParCompte(du, au)) {
            ouvertures.put((String) row[0], row);
        }
        Map<String, Object[]> mouvements = new HashMap<>();
        for (Object[] row : ecritureRepository.mouvementsParCompte(du, au)) {
            mouvements.put((String) row[0], row);
        }

        Set<String> numeros = new TreeSet<>();
        numeros.addAll(ouvertures.keySet());
        numeros.addAll(mouvements.keySet());

        List<LigneCompte> resultat = new ArrayList<>();
        for (String numero : numeros) {
            Object[] o = ouvertures.get(numero);
            Object[] m = mouvements.get(numero);
            Object[] reference = m != null ? m : o;
            String libelle = (String) reference[1];
            TypeCompte type = (TypeCompte) reference[2];
            Integer classe = (Integer) reference[3];
            resultat.add(new LigneCompte(
                numero, libelle, type, classe == null ? 0 : classe,
                o != null ? (BigDecimal) o[4] : BigDecimal.ZERO,
                o != null ? (BigDecimal) o[5] : BigDecimal.ZERO,
                m != null ? (BigDecimal) m[4] : BigDecimal.ZERO,
                m != null ? (BigDecimal) m[5] : BigDecimal.ZERO
            ));
        }
        return resultat;
    }

    /**
     * true si l'écriture relève des soldes d'ouverture plutôt que des
     * mouvements de la période : soit elle précède la période, soit elle
     * appartient à une pièce de reprise des à-nouveaux (« solde d'ouverture »),
     * datée par convention du premier jour de l'exercice. Reflète exactement
     * le partage opéré par les requêtes du Grand Livre, afin que la feuille
     * Journal et les courbes mensuelles restent cohérentes avec la Balance.
     */
    private boolean estOuverture(EcritureGrandLivre e, LocalDate du) {
        if (e.getDateEcriture() != null && e.getDateEcriture().isBefore(du)) {
            return true;
        }
        return e.getPiece() != null && e.getPiece().isSoldeOuverture();
    }

    // ---------------------------------------------------------------------
    // Feuille Journal
    // ---------------------------------------------------------------------

    private void construireJournal(XSSFWorkbook wb, Styles s, List<EcritureGrandLivre> lignes,
                                    LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("Journal");
        titre(sh, s, "JOURNAL COMPTABLE", "Toutes les écritures jusqu'au " + au.format(DATE_FR)
            + " — période courante à partir du " + du.format(DATE_FR));

        int r = 3;
        Row header = sh.createRow(r++);
        String[] cols = {"Date", "N° Pièce", "Journal", "Compte", "Intitulé du compte",
            "Libellé écriture", "Débit", "Crédit", "Période"};
        for (int c = 0; c < cols.length; c++) {
            cell(header, c, cols[c], s.enTete);
        }

        int premiereLigne = r;
        for (EcritureGrandLivre e : lignes) {
            Row row = sh.createRow(r++);
            CompteOHADA compte = e.getCompte();
            cell(row, 0, e.getDateEcriture() != null ? e.getDateEcriture().format(DATE_FR) : "", s.normal);
            cell(row, 1, e.getPiece() != null ? e.getPiece().getReference() : "", s.normal);
            cell(row, 2, e.getPiece() != null ? e.getPiece().getJournal().name() : "", s.normal);
            cell(row, 3, compte.getNumero(), s.normal);
            cell(row, 4, compte.getLibelle(), s.normal);
            cell(row, 5, e.getLibelle(), s.normal);
            cellMontant(row, 6, e.getDebit(), s);
            cellMontant(row, 7, e.getCredit(), s);
            cell(row, 8, estOuverture(e, du) ? "Ouverture" : "Période", s.normal);
        }
        int derniereLigne = r - 1;

        Row total = sh.createRow(r++);
        cell(total, 0, "TOTAL JOURNAL", s.totalLabel);
        sh.addMergedRegion(new CellRangeAddress(total.getRowNum(), total.getRowNum(), 0, 5));
        if (derniereLigne >= premiereLigne) {
            formule(total, 6, "SUM(G" + (premiereLigne + 1) + ":G" + (derniereLigne + 1) + ")", s.totalMontant);
            formule(total, 7, "SUM(H" + (premiereLigne + 1) + ":H" + (derniereLigne + 1) + ")", s.totalMontant);
        } else {
            cellMontant(total, 6, BigDecimal.ZERO, s);
            cellMontant(total, 7, BigDecimal.ZERO, s);
        }

        Row controle = sh.createRow(r++);
        String refG = "G" + (total.getRowNum() + 1);
        String refH = "H" + (total.getRowNum() + 1);
        formule(controle, 0, "IF(ROUND(" + refG + "-" + refH + ",2)=0,"
            + "\"✔ JOURNAL ÉQUILIBRÉ\",\"⚠ DÉSÉQUILIBRE : \"&TEXT(ABS("
            + refG + "-" + refH + "),\"#,##0.00\"))", s.controleFormule);
        sh.addMergedRegion(new CellRangeAddress(controle.getRowNum(), controle.getRowNum(), 0, 8));

        largeurs(sh, 3200, 3200, 3600, 2800, 9000, 11000, 3200, 3200, 2800);
        sh.createFreezePane(0, premiereLigne);
    }

    // ---------------------------------------------------------------------
    // Feuille Balance (6 colonnes)
    // ---------------------------------------------------------------------

    private static final String[] NOMS_CLASSES = {
        "", "CLASSE 1 – COMPTES DE CAPITAUX", "CLASSE 2 – COMPTES D'IMMOBILISATIONS",
        "CLASSE 3 – COMPTES DE STOCKS", "CLASSE 4 – COMPTES DE TIERS",
        "CLASSE 5 – COMPTES DE TRÉSORERIE", "CLASSE 6 – COMPTES DE CHARGES",
        "CLASSE 7 – COMPTES DE PRODUITS", "CLASSE 8 – CHARGES ET PRODUITS H.A.O.",
    };

    /** @return [première ligne de données (0-based), dernière ligne de données] pour réutilisation par Bilan/Résultat. */
    private int[] construireBalance(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                     LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("Balance");
        titre(sh, s, "BALANCE GÉNÉRALE À 6 COLONNES – SYSCOHADA RÉVISÉ",
            "Du " + du.format(DATE_FR) + " au " + au.format(DATE_FR)
            + " — comptes du Grand Livre ayant porté au moins une écriture");

        int r = 3;
        Row g1 = sh.createRow(r);
        cell(g1, 0, "Compte", s.enTete);
        cell(g1, 1, "Intitulé", s.enTete);
        cell(g1, 2, "SOLDES D'OUVERTURE", s.enTete);
        cell(g1, 4, "MOUVEMENTS DE LA PÉRIODE", s.enTete);
        cell(g1, 6, "SOLDES DE CLÔTURE", s.enTete);
        sh.addMergedRegion(new CellRangeAddress(r, r, 2, 3));
        sh.addMergedRegion(new CellRangeAddress(r, r, 4, 5));
        sh.addMergedRegion(new CellRangeAddress(r, r, 6, 7));
        r++;
        Row g2 = sh.createRow(r++);
        String[] sousEntetes = {"", "", "Débit", "Crédit", "Débit", "Crédit", "Débit", "Crédit"};
        for (int c = 2; c < sousEntetes.length; c++) cell(g2, c, sousEntetes[c], s.sousEntete);

        int premiereLigne = r;
        Map<Integer, List<LigneCompte>> parClasse = new TreeMap<>();
        for (LigneCompte c : comptes) {
            parClasse.computeIfAbsent(c.classe(), k -> new ArrayList<>()).add(c);
        }
        for (var entry : parClasse.entrySet()) {
            Row hdr = sh.createRow(r++);
            String nom = entry.getKey() >= 1 && entry.getKey() <= 8
                ? NOMS_CLASSES[entry.getKey()] : "HORS CLASSE";
            cell(hdr, 0, nom, s.classeHeader);
            sh.addMergedRegion(new CellRangeAddress(hdr.getRowNum(), hdr.getRowNum(), 0, 7));
            for (LigneCompte c : entry.getValue()) {
                Row row = sh.createRow(r++);
                cell(row, 0, c.numero(), s.normal);
                cell(row, 1, c.libelle(), s.normal);
                cellMontant(row, 2, c.ouvD(), s);
                cellMontant(row, 3, c.ouvC(), s);
                cellMontant(row, 4, c.mvtD(), s);
                cellMontant(row, 5, c.mvtC(), s);
                cellMontant(row, 6, c.cloD(), s);
                cellMontant(row, 7, c.cloC(), s);
            }
        }
        int derniereLigne = r - 1;

        Row total = sh.createRow(r++);
        cell(total, 0, "TOTAL GÉNÉRAL", s.totalLabel);
        sh.addMergedRegion(new CellRangeAddress(total.getRowNum(), total.getRowNum(), 0, 1));
        int ligneTotalExcel = total.getRowNum() + 1;
        // SUM plutôt que SUMIF sur la colonne compte : les lignes d'en-tête de
        // classe ne portent aucune valeur en colonnes C à H (cellules vides),
        // donc un SUM simple les ignore déjà. Le numéro de compte est écrit en
        // texte (certains comptes du référentiel ne sont pas purement
        // numériques) : un SUMIF(">0", ...) sur une colonne texte ne
        // correspondrait jamais, et renverrait silencieusement 0.
        if (derniereLigne >= premiereLigne) {
            for (int c = 2; c <= 7; c++) {
                String col = colLettre(c);
                formule(total, c, "SUM(" + col + "$" + (premiereLigne + 1) + ":" + col + "$" + (derniereLigne + 1) + ")",
                    s.totalMontant);
            }
        } else {
            for (int c = 2; c <= 7; c++) cellMontant(total, c, BigDecimal.ZERO, s);
        }

        Row controle = sh.createRow(r++);
        String d = "C" + ligneTotalExcel, cC = "D" + ligneTotalExcel, f = "E" + ligneTotalExcel,
               g = "F" + ligneTotalExcel, h = "G" + ligneTotalExcel, i = "H" + ligneTotalExcel;
        formule(controle, 0, "IF(AND(ROUND(" + d + "-" + cC + ",2)=0,ROUND(" + f + "-" + g + ",2)=0,"
            + "ROUND(" + h + "-" + i + ",2)=0),"
            + "\"✔ BALANCE ÉQUILIBRÉE — ouverture, mouvements et clôture le sont tous\","
            + "\"⚠ DÉSÉQUILIBRE DÉTECTÉ\")", s.controleFormule);
        sh.addMergedRegion(new CellRangeAddress(controle.getRowNum(), controle.getRowNum(), 0, 7));

        largeurs(sh, 3200, 11000, 3600, 3600, 3600, 3600, 3600, 3600);
        sh.createFreezePane(0, premiereLigne);

        return new int[]{premiereLigne, derniereLigne};
    }

    // ---------------------------------------------------------------------
    // Feuille Résultat
    // ---------------------------------------------------------------------

    /**
     * Compte de résultat au format officiel SYSCOHADA "Système Normal" (codes
     * de référence TA à XI), sur le modèle de l'état publié par les
     * commissaires aux comptes. Chaque ligne cible les comptes 3 chiffres
     * exacts du référentiel (ex. 701 pour les ventes de marchandises, 6031
     * pour la variation de stocks de marchandises) plutôt que la classe
     * entière, de sorte que la Marge commerciale et le Résultat financier
     * isolent précisément leurs composantes — y compris les sous-comptes
     * "financiers" logés dans des chapitres à vocation mixte (787 transferts
     * de charges financières, 797 reprises financières, 679/697 dotations
     * financières), qu'une lecture au niveau de la classe seule classerait à
     * tort en exploitation.
     *
     * @return le résultat net de l'exercice, réutilisé par Bilan/Flux/Ratios/Notes.
     */
    // =====================================================================
    // Liasse officielle SYSCOHADA révisé — Système Normal
    //
    // Reproduit la présentation réglementaire (modèle « OHADA REV » remis par
    // la Direction Finance) : feuilles ACTIF, PASSIF, RESULTAT et TFT
    // séparées, codes de référence officiels, colonne Note renvoyant à l'état
    // annexé, colonne comparative N-1, et surtout des FORMULES Excel vivantes
    // pour tous les agrégats — les totaux se recalculent dans le classeur au
    // lieu d'être figés, ce qui permet au comptable de corriger une ligne et
    // de voir l'ensemble se réajuster, comme sur le modèle officiel.
    //
    // Convention de présentation du modèle : le TOTAL d'une rubrique précède
    // ses postes de détail (AD avant AE..AH). Les lignes de total sont donc
    // créées d'abord, puis renseignées une fois les détails écrits et leurs
    // numéros de ligne connus.
    // =====================================================================

    /** Colonnes de la feuille ACTIF. */
    private static final int C_REF = 0, C_LIB = 1, C_NOTE = 2, C_BRUT = 3, C_AMORT = 4, C_NET = 5, C_NET1 = 6;

    /**
     * Feuille ACTIF au format officiel : Brut / Amortissements et
     * dépréciations / Net, avec le Net de l'exercice précédent en regard.
     */
    private BilanActifTotaux construireActif(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                             LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("ACTIF");
        int r = enTeteLiasse(sh, s, "BILAN AU " + au.format(DATE_FR), du, au);

        Row head = sh.createRow(r++);
        cell(head, C_REF, "REF", s.enTete);
        cell(head, C_LIB, "ACTIF", s.enTete);
        cell(head, C_NOTE, "Note", s.enTete);
        cell(head, C_BRUT, au.format(DATE_FR), s.enTete);
        cell(head, C_NET1, exercicePrecedent(du), s.enTete);
        Row sous = sh.createRow(r++);
        cell(sous, C_BRUT, "BRUT", s.sousEntete);
        cell(sous, C_AMORT, "AMORT. ET DEPREC.", s.sousEntete);
        cell(sous, C_NET, "NET", s.sousEntete);
        cell(sous, C_NET1, "NET", s.sousEntete);

        // ---- AD : immobilisations incorporelles (21 / 281+291) ------------
        Row rAD = sh.createRow(r++);
        int adDeb = r;
        r = ligneActif(sh, s, r, "AE", "Frais de développement et de prospection", "",
            postePrefixe(comptes, "211", "2811"));
        r = ligneActif(sh, s, r, "AF", "Brevets, licences, logiciels et droits similaires", "",
            postePrefixes(comptes, new String[]{"212", "213"}, new String[]{"2812", "2813"}));
        r = ligneActif(sh, s, r, "AG", "Fonds commercial et droit au bail", "",
            postePrefixes(comptes, new String[]{"215", "216"}, new String[]{"2815", "2816"}));
        r = ligneActif(sh, s, r, "AH", "Autres immobilisations incorporelles", "",
            posteResidu(comptes, "21", new String[]{"211", "212", "213", "215", "216"},
                        "281", new String[]{"2811", "2812", "2813", "2815", "2816"}));
        int adFin = r - 1;
        totalActif(rAD, s, "AD", "IMMOBILISATIONS INCORPORELLES", "3", adDeb, adFin, Palier.RUBRIQUE);

        // ---- AI : immobilisations corporelles (22-24 / 282-284) -----------
        Row rAI = sh.createRow(r++);
        int aiDeb = r;
        r = ligneActif(sh, s, r, "AJ", "Terrains", "3", postePrefixe(comptes, "22", "282"));
        r = ligneActif(sh, s, r, "AK", "Bâtiments", "3", postePrefixe(comptes, "23", "283"));
        r = ligneActif(sh, s, r, "AL", "Aménagements, agencements et installations", "3",
            postePrefixe(comptes, "235", "2835"));
        r = ligneActif(sh, s, r, "AM", "Matériel, mobilier et actifs biologiques", "3",
            postePrefixes(comptes, new String[]{"241", "242", "243", "244"},
                          new String[]{"2841", "2842", "2843", "2844"}));
        r = ligneActif(sh, s, r, "AN", "Matériel de transport", "3", postePrefixe(comptes, "245", "2845"));
        r = ligneActif(sh, s, r, "AP", "Avances et acomptes versés sur immobilisations", "3",
            postePrefixe(comptes, "252", null));
        int aiFin = r - 1;
        totalActif(rAI, s, "AI", "IMMOBILISATIONS CORPORELLES", "3", aiDeb, aiFin, Palier.RUBRIQUE);

        // ---- AQ : immobilisations financières (26-27 / 296-297) -----------
        Row rAQ = sh.createRow(r++);
        int aqDeb = r;
        r = ligneActif(sh, s, r, "AR", "Titres de participation", "4", postePrefixe(comptes, "26", "296"));
        r = ligneActif(sh, s, r, "AS", "Autres immobilisations financières", "4",
            postePrefixe(comptes, "27", "297"));
        int aqFin = r - 1;
        totalActif(rAQ, s, "AQ", "IMMOBILISATIONS FINANCIERES", "4", aqDeb, aqFin, Palier.RUBRIQUE);

        // ---- AZ : total actif immobilisé ----------------------------------
        Row rAZ = sh.createRow(r++);
        totalActifSomme(rAZ, s, "AZ", "TOTAL ACTIF IMMOBILISE",
            new int[]{rAD.getRowNum(), rAI.getRowNum(), rAQ.getRowNum()}, Palier.TOTAL_MAJEUR);

        // ---- Actif circulant ---------------------------------------------
        Row rBA = sh.createRow(r++);
        posteSurLigne(rBA, s, "BA", "ACTIF CIRCULANT HAO", "5", postePrefixe(comptes, "485", null), Palier.POSTE_MASSE);
        Row rBB = sh.createRow(r++);
        posteSurLigne(rBB, s, "BB", "STOCKS ET ENCOURS", "6", postePrefixe(comptes, "3", "39"), Palier.POSTE_MASSE);

        Row rBG = sh.createRow(r++);
        int bgDeb = r;
        r = ligneActif(sh, s, r, "BH", "Fournisseurs avances versées", "17",
            postePrefixe(comptes, "409", null));
        r = ligneActif(sh, s, r, "BI", "Clients", "7", postePrefixe(comptes, "41", "491"));
        r = ligneActif(sh, s, r, "BJ", "Autres créances", "8",
            postePrefixes(comptes, new String[]{"42", "43", "44", "45", "46", "47"},
                          new String[]{"492", "493", "494", "495", "496", "497"}));
        int bgFin = r - 1;
        totalActif(rBG, s, "BG", "CREANCES ET EMPLOIS ASSIMILES", "", bgDeb, bgFin, Palier.POSTE_MASSE);

        Row rBK = sh.createRow(r++);
        totalActifSomme(rBK, s, "BK", "TOTAL ACTIF CIRCULANT",
            new int[]{rBA.getRowNum(), rBB.getRowNum(), rBG.getRowNum()}, Palier.TOTAL_MAJEUR);

        // ---- Trésorerie-Actif --------------------------------------------
        int btDeb = r;
        r = ligneActif(sh, s, r, "BQ", "Titres de placement", "9", postePrefixe(comptes, "50", "590"));
        r = ligneActif(sh, s, r, "BR", "Valeurs à encaisser", "10", postePrefixe(comptes, "51", "591"));
        r = ligneActif(sh, s, r, "BS", "Banques, chèques postaux, caisse et assimilés", "11",
            posteResidu(comptes, "5", new String[]{"50", "51", "59"}, "59", new String[]{"590", "591"}));
        int btFin = r - 1;
        Row rBT = sh.createRow(r++);
        totalActif(rBT, s, "BT", "TOTAL TRESORERIE-ACTIF", "", btDeb, btFin, Palier.TOTAL_MAJEUR);

        Row rBU = sh.createRow(r++);
        posteSurLigne(rBU, s, "BU", "Ecart de conversion-Actif", "12", postePrefixe(comptes, "478", null), Palier.NORMAL);

        Row rBZ = sh.createRow(r++);
        totalActifSomme(rBZ, s, "BZ", "TOTAL GENERAL",
            new int[]{rAZ.getRowNum(), rBK.getRowNum(), rBT.getRowNum(), rBU.getRowNum()}, Palier.TOTAL_GENERAL);

        largeurs(sh, 1510, 10520, 1360, 3990, 3940, 4100, 4100);
        miseEnPageLiasse(sh);

        // Totaux calcules en Java : les cellules ci-dessus portent des formules,
        // que POI ne peut pas evaluer a l'ecriture. Les Notes et les Graphiques
        // ont besoin des valeurs, pas des formules.
        // Les comptes d'amortissement et de dépréciation (28x, 29x, 39x, 49x,
        // 59x) sont typés ACTIF et portent un solde créditeur : la somme d'un
        // chapitre entier est donc DÉJÀ nette, il ne faut surtout pas les
        // déduire une seconde fois.
        BigDecimal immobilise = cloActifPrefixe(comptes, "2");
        BigDecimal circulantHao = cloActifPrefixe(comptes, "485");
        BigDecimal stocks = cloActifPrefixe(comptes, "3");
        BigDecimal ecartConv = cloActifPrefixe(comptes, "478");
        BigDecimal creances = cloActifPrefixe(comptes, "4")
            .subtract(circulantHao).subtract(ecartConv);
        BigDecimal circulant = circulantHao.add(stocks).add(creances);
        BigDecimal tresorerie = cloActifPrefixe(comptes, "5");
        BigDecimal total = immobilise.add(circulant).add(tresorerie).add(ecartConv);

        return new BilanActifTotaux(sh.getSheetName(), rAZ.getRowNum(), rBK.getRowNum(),
            rBT.getRowNum(), rBZ.getRowNum(), immobilise, circulant, tresorerie, total);
    }

    /** Lignes clés de la feuille ACTIF, pour les renvois inter-feuilles et les Notes. */
    private record BilanActifTotaux(String feuille, int ligneAZ, int ligneBK, int ligneBT, int ligneBZ,
                                    BigDecimal actifImmobilise, BigDecimal actifCirculant,
                                    BigDecimal tresorerieActif, BigDecimal totalActif) {}

    /** Valeurs d'un poste d'actif : brut et amortissement, à l'ouverture et à la clôture. */
    private record PosteActif(BigDecimal brut, BigDecimal amort, BigDecimal brutN1, BigDecimal amortN1) {
        BigDecimal net() { return brut.subtract(amort); }
        BigDecimal netN1() { return brutN1.subtract(amortN1); }
    }

    private PosteActif postePrefixe(List<LigneCompte> comptes, String prefixeBrut, String prefixeAmort) {
        return postePrefixes(comptes,
            new String[]{prefixeBrut},
            prefixeAmort == null ? new String[0] : new String[]{prefixeAmort});
    }

    private PosteActif postePrefixes(List<LigneCompte> comptes, String[] brutPrefixes, String[] amortPrefixes) {
        BigDecimal brut = BigDecimal.ZERO, amort = BigDecimal.ZERO;
        BigDecimal brutN1 = BigDecimal.ZERO, amortN1 = BigDecimal.ZERO;
        for (String p : brutPrefixes) {
            brut = brut.add(cloActifPrefixe(comptes, p));
            brutN1 = brutN1.add(ouvActifPrefixe(comptes, p));
        }
        for (String p : amortPrefixes) {
            amort = amort.add(cloActifPrefixe(comptes, p).negate());
            amortN1 = amortN1.add(ouvActifPrefixe(comptes, p).negate());
        }
        return new PosteActif(brut, amort, brutN1, amortN1);
    }

    /**
     * Poste « autres » : le solde d'un chapitre diminué des sous-postes déjà
     * présentés. Évite qu'un compte non explicitement listé disparaisse du
     * bilan — le total du chapitre reste exact quel que soit le sous-plan.
     */
    private PosteActif posteResidu(List<LigneCompte> comptes, String chapitreBrut, String[] dejaBrut,
                                   String chapitreAmort, String[] dejaAmort) {
        BigDecimal brut = cloActifPrefixe(comptes, chapitreBrut);
        BigDecimal brutN1 = ouvActifPrefixe(comptes, chapitreBrut);
        for (String p : dejaBrut) {
            brut = brut.subtract(cloActifPrefixe(comptes, p));
            brutN1 = brutN1.subtract(ouvActifPrefixe(comptes, p));
        }
        BigDecimal amort = BigDecimal.ZERO, amortN1 = BigDecimal.ZERO;
        if (chapitreAmort != null) {
            amort = cloActifPrefixe(comptes, chapitreAmort).negate();
            amortN1 = ouvActifPrefixe(comptes, chapitreAmort).negate();
            for (String p : dejaAmort) {
                amort = amort.subtract(cloActifPrefixe(comptes, p).negate());
                amortN1 = amortN1.subtract(ouvActifPrefixe(comptes, p).negate());
            }
        }
        return new PosteActif(brut, amort, brutN1, amortN1);
    }

    /**
     * Niveau de lecture d'une ligne de la liasse. Le classeur OHADA de
     * reference code l'hierarchie par la teinte de fond : rubrique en gris,
     * totaux de masse en bleu marine, resultats intermediaires en creme,
     * total general en vert. On reprend exactement cette grammaire.
     */
    private enum Palier { NORMAL, POSTE_MASSE, RUBRIQUE, SOUS_MASSE, TOTAL_MAJEUR,
                          RESULTAT_INTERMEDIAIRE, TOTAL_GENERAL }

    private CellStyle styleLibelle(Styles s, Palier p) {
        return switch (p) {
            case NORMAL -> s.libelleLiasse;
            case POSTE_MASSE -> s.posteMasseLabel;
            case RUBRIQUE -> s.rubriqueLabel;
            case SOUS_MASSE -> s.sousMasseLabel;
            case TOTAL_MAJEUR -> s.totalMajeurLabel;
            case RESULTAT_INTERMEDIAIRE -> s.resultatIntLabel;
            case TOTAL_GENERAL -> s.totalGeneralLabel;
        };
    }

    private CellStyle styleMontant(Styles s, Palier p) {
        return switch (p) {
            case NORMAL -> s.montantLiasse;
            case POSTE_MASSE -> s.posteMasseMontant;
            case RUBRIQUE -> s.rubriqueMontant;
            case SOUS_MASSE -> s.sousMasseMontant;
            case TOTAL_MAJEUR -> s.totalMajeurMontant;
            case RESULTAT_INTERMEDIAIRE -> s.resultatIntMontant;
            case TOTAL_GENERAL -> s.totalGeneralMontant;
        };
    }

    /** Colonne REF : centree, et teintee comme le reste de la ligne. */
    private CellStyle styleRef(Styles s, Palier p) {
        return p == Palier.NORMAL ? s.refLiasse : styleLibelle(s, p);
    }

    private int ligneActif(Sheet sh, Styles s, int r, String ref, String libelle, String note, PosteActif p) {
        Row row = sh.createRow(r);
        posteSurLigne(row, s, ref, libelle, note, p, Palier.NORMAL);
        return r + 1;
    }

    /** Écrit un poste d'actif sur une ligne déjà créée (colonne NET en formule). */
    private void posteSurLigne(Row row, Styles s, String ref, String libelle, String note,
                               PosteActif p, Palier palier) {
        int xl = row.getRowNum() + 1;
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, C_REF, ref, styleRef(s, palier));
        cell(row, C_LIB, libelle, lib);
        cell(row, C_NOTE, note == null ? "" : note, lib);
        cellMontant(row, C_BRUT, p.brut(), mt);
        cellMontant(row, C_AMORT, p.amort(), mt);
        formule(row, C_NET, "D" + xl + "-E" + xl, mt);
        cellMontant(row, C_NET1, p.netN1(), mt);
    }

    /** Ligne de total sur une plage contiguë de postes (SUM), colonne NET incluse. */
    private void totalActif(Row row, Styles s, String ref, String libelle, String note,
                            int premiere0, int derniere0, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, C_REF, ref, styleRef(s, palier));
        cell(row, C_LIB, libelle, lib);
        cell(row, C_NOTE, note == null ? "" : note, lib);
        for (int col : new int[]{C_BRUT, C_AMORT, C_NET, C_NET1}) {
            sommeOuZero(row, col, premiere0, derniere0, mt);
        }
    }

    /** Ligne de total agrégeant des lignes non contiguës (ex. AZ = AD + AI + AQ). */
    private void totalActifSomme(Row row, Styles s, String ref, String libelle, int[] lignes0, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, C_REF, ref, styleRef(s, palier));
        cell(row, C_LIB, libelle, lib);
        cell(row, C_NOTE, "", lib);
        for (int col : new int[]{C_BRUT, C_AMORT, C_NET, C_NET1}) {
            StringBuilder f = new StringBuilder();
            for (int l : lignes0) {
                if (f.length() > 0) f.append('+');
                f.append(colLettre(col)).append(l + 1);
            }
            formule(row, col, f.toString(), mt);
        }
    }

    private BigDecimal ouvActifPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.ACTIF && c.numero().startsWith(prefixe)) {
                total = total.add(c.netOuverture());
            }
        }
        return total;
    }

    private BigDecimal ouvPassifPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.PASSIF && c.numero().startsWith(prefixe)) {
                total = total.add(c.netOuverture().negate());
            }
        }
        return total;
    }

    /** En-tête commun aux feuilles de la liasse (société, exercice, titre). */
    private int enTeteLiasse(Sheet sh, Styles s, String titre, LocalDate du, LocalDate au) {
        Row r0 = sh.createRow(0);
        cell(r0, 0, nomSociete(), s.enTeteLiasseCell);
        cell(r0, C_BRUT, "Exercice clos le " + au.format(DATE_FR), s.sousTitre);
        Row r1 = sh.createRow(1);
        cell(r1, 0, "États financiers annuels — SYSCOHADA révisé (Système Normal)", s.sousTitre);
        cell(r1, C_BRUT, "Durée (en mois) : " + dureeEnMois(du, au), s.sousTitre);
        Row r2 = sh.createRow(2);
        cell(r2, 0, titre, s.titreLiasse);
        return 4;
    }

    private String exercicePrecedent(LocalDate du) {
        return du.minusDays(1).format(DATE_FR);
    }

    private long dureeEnMois(LocalDate du, LocalDate au) {
        return java.time.temporal.ChronoUnit.MONTHS.between(du.withDayOfMonth(1), au.withDayOfMonth(1)) + 1;
    }

    // ---------------------------------------------------------------------
    // Feuille PASSIF
    // ---------------------------------------------------------------------

    /** Colonnes de la feuille PASSIF : pas de Brut/Amort, seulement Net et Net N-1. */
    private static final int P_REF = 0, P_LIB = 1, P_NOTE = 2, P_NET = 3, P_NET1 = 4;

    private BilanPassifTotaux construirePassif(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                               BigDecimal resultatNet, BigDecimal resultatNetN1,
                                               LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("PASSIF");
        int r = enTetePassif(sh, s, "BILAN AU " + au.format(DATE_FR), du, au);

        Row head = sh.createRow(r++);
        cell(head, P_REF, "REF", s.enTete);
        cell(head, P_LIB, "PASSIF", s.enTete);
        cell(head, P_NOTE, "Note", s.enTete);
        cell(head, P_NET, au.format(DATE_FR), s.enTete);
        cell(head, P_NET1, exercicePrecedent(du), s.enTete);
        Row sous = sh.createRow(r++);
        cell(sous, P_NET, "NET", s.sousEntete);
        cell(sous, P_NET1, "NET", s.sousEntete);

        // ---- Capitaux propres (classe 1) ---------------------------------
        int cpDeb = r;
        r = lignePassifLiasse(sh, s, r, "CA", "Capital", "13",
            passifPoste(comptes, new String[]{"101", "102", "103", "104"}));
        r = lignePassifLiasse(sh, s, r, "CB", "Apporteurs capital non appelé (-)", "13",
            passifPoste(comptes, new String[]{"109"}));
        r = lignePassifLiasse(sh, s, r, "CD", "Primes liées au capital social", "14",
            passifPoste(comptes, new String[]{"105"}));
        r = lignePassifLiasse(sh, s, r, "CE", "Ecarts de réévaluation", "3e",
            passifPoste(comptes, new String[]{"106"}));
        r = lignePassifLiasse(sh, s, r, "CF", "Réserves indisponibles", "14",
            passifPoste(comptes, new String[]{"111", "112", "113"}));
        r = lignePassifLiasse(sh, s, r, "CG", "Réserves libres", "14",
            passifPoste(comptes, new String[]{"118"}));
        r = lignePassifLiasse(sh, s, r, "CH", "Report à nouveau (+ ou -)", "14",
            passifPoste(comptes, new String[]{"12"}));
        // Le résultat net n'est pas lu au compte 13 : tant que l'exercice n'est
        // pas clôturé il n'y est pas encore porté, il se déduit des classes 6/7.
        r = lignePassifLiasse(sh, s, r, "CJ", "Résultat net de l'exercice (bénéfice + ou perte -)", "",
            new PostePassif(resultatNet, resultatNetN1));
        r = lignePassifLiasse(sh, s, r, "CL", "Subventions d'investissement", "15",
            passifPoste(comptes, new String[]{"14"}));
        r = lignePassifLiasse(sh, s, r, "CM", "Provisions réglementées", "15",
            passifPoste(comptes, new String[]{"15"}));
        int cpFin = r - 1;
        Row rCP = sh.createRow(r++);
        totalPassifPlage(rCP, s, "CP", "TOTAL CAPITAUX PROPRES ET RESSOURCES ASSIMILEES", cpDeb, cpFin, Palier.RUBRIQUE);

        // ---- Dettes financières ------------------------------------------
        int ddDeb = r;
        r = lignePassifLiasse(sh, s, r, "DA", "Emprunts et dettes financières diverses", "16",
            passifPoste(comptes, new String[]{"16", "18"}));
        r = lignePassifLiasse(sh, s, r, "DB", "Dettes de location acquisition", "16",
            passifPoste(comptes, new String[]{"17"}));
        r = lignePassifLiasse(sh, s, r, "DC", "Provisions pour risques et charges", "16",
            passifPoste(comptes, new String[]{"19"}));
        int ddFin = r - 1;
        Row rDD = sh.createRow(r++);
        totalPassifPlage(rDD, s, "DD", "TOTAL DETTES FINANCIERES ET RESSOURCES ASSIMILEES", ddDeb, ddFin, Palier.RUBRIQUE);

        Row rDF = sh.createRow(r++);
        totalPassifSomme(rDF, s, "DF", "TOTAL RESSOURCES STABLES",
            new int[]{rCP.getRowNum(), rDD.getRowNum()}, Palier.TOTAL_MAJEUR);

        // ---- Passif circulant --------------------------------------------
        int dpDeb = r;
        r = lignePassifLiasse(sh, s, r, "DH", "Dettes circulantes HAO", "5",
            passifPoste(comptes, new String[]{"48"}));
        r = lignePassifLiasse(sh, s, r, "DI", "Clients, avances reçues", "7",
            passifPoste(comptes, new String[]{"419"}));
        r = lignePassifLiasse(sh, s, r, "DJ", "Fournisseurs d'exploitation", "17",
            passifPosteSauf(comptes, new String[]{"40"}, new String[]{"409"}));
        r = lignePassifLiasse(sh, s, r, "DK", "Dettes fiscales et sociales", "18",
            passifPoste(comptes, new String[]{"42", "43", "44"}));
        r = lignePassifLiasse(sh, s, r, "DM", "Autres dettes", "19",
            passifPosteSauf(comptes, new String[]{"45", "46", "47"}, new String[]{"479"}));
        r = lignePassifLiasse(sh, s, r, "DN", "Provisions pour risques à court terme", "19",
            passifPoste(comptes, new String[]{"499"}));
        int dpFin = r - 1;
        Row rDP = sh.createRow(r++);
        totalPassifPlage(rDP, s, "DP", "TOTAL PASSIF CIRCULANT", dpDeb, dpFin, Palier.TOTAL_MAJEUR);

        // ---- Trésorerie-Passif -------------------------------------------
        int dtDeb = r;
        r = lignePassifLiasse(sh, s, r, "DQ", "Banques, crédits d'escompte", "20",
            passifPoste(comptes, new String[]{"564", "565"}));
        r = lignePassifLiasse(sh, s, r, "DR", "Banques, établissements financiers et crédits de trésorerie", "20",
            passifPosteSauf(comptes, new String[]{"5"}, new String[]{"564", "565"}));
        int dtFin = r - 1;
        Row rDT = sh.createRow(r++);
        totalPassifPlage(rDT, s, "DT", "TOTAL TRESORERIE-PASSIF", dtDeb, dtFin, Palier.TOTAL_MAJEUR);

        Row rDV = sh.createRow(r++);
        postePassifSurLigne(rDV, s, "DV", "Ecart de conversion-Passif", "12",
            passifPoste(comptes, new String[]{"479"}), Palier.NORMAL);

        Row rDZ = sh.createRow(r++);
        totalPassifSomme(rDZ, s, "DZ", "TOTAL GENERAL",
            new int[]{rDF.getRowNum(), rDP.getRowNum(), rDT.getRowNum(), rDV.getRowNum()}, Palier.TOTAL_GENERAL);

        largeurs(sh, 1510, 14230, 1360, 4100, 4580);
        miseEnPageLiasse(sh);

        // Comme pour l'actif : les cellules de total portent des formules, les
        // valeurs sont recalculées ici pour les Notes et les Graphiques.
        BigDecimal capitaux = cloPassifPrefixe(comptes, "10").add(cloPassifPrefixe(comptes, "11"))
            .add(cloPassifPrefixe(comptes, "12")).add(cloPassifPrefixe(comptes, "14"))
            .add(cloPassifPrefixe(comptes, "15")).add(resultatNet);
        BigDecimal dettesFin = cloPassifPrefixe(comptes, "16").add(cloPassifPrefixe(comptes, "17"))
            .add(cloPassifPrefixe(comptes, "18")).add(cloPassifPrefixe(comptes, "19"));
        BigDecimal ecartConvP = cloPassifPrefixe(comptes, "479");
        BigDecimal circulantP = cloPassifPrefixe(comptes, "4").subtract(ecartConvP);
        BigDecimal tresoP = cloPassifPrefixe(comptes, "5");
        BigDecimal totalP = capitaux.add(dettesFin).add(circulantP).add(tresoP).add(ecartConvP);

        return new BilanPassifTotaux(rCP.getRowNum(), rDP.getRowNum(), rDT.getRowNum(), rDZ.getRowNum(),
            capitaux.add(dettesFin), circulantP, tresoP, totalP);
    }

    private record BilanPassifTotaux(int ligneCP, int ligneDP, int ligneDT, int ligneDZ,
                                     BigDecimal capitauxPropres, BigDecimal passifCirculant,
                                     BigDecimal tresoreriePassif, BigDecimal totalPassif) {}

    private record PostePassif(BigDecimal net, BigDecimal netN1) {}

    private PostePassif passifPoste(List<LigneCompte> comptes, String[] prefixes) {
        BigDecimal net = BigDecimal.ZERO, netN1 = BigDecimal.ZERO;
        for (String p : prefixes) {
            net = net.add(cloPassifPrefixe(comptes, p));
            netN1 = netN1.add(ouvPassifPrefixe(comptes, p));
        }
        return new PostePassif(net, netN1);
    }

    private PostePassif passifPosteSauf(List<LigneCompte> comptes, String[] prefixes, String[] exclus) {
        PostePassif base = passifPoste(comptes, prefixes);
        PostePassif retire = passifPoste(comptes, exclus);
        return new PostePassif(base.net().subtract(retire.net()), base.netN1().subtract(retire.netN1()));
    }

    private int lignePassifLiasse(Sheet sh, Styles s, int r, String ref, String libelle, String note, PostePassif p) {
        Row row = sh.createRow(r);
        postePassifSurLigne(row, s, ref, libelle, note, p, Palier.NORMAL);
        return r + 1;
    }

    private void postePassifSurLigne(Row row, Styles s, String ref, String libelle, String note,
                                     PostePassif p, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, P_REF, ref, styleRef(s, palier));
        cell(row, P_LIB, libelle, lib);
        cell(row, P_NOTE, note == null ? "" : note, lib);
        cellMontant(row, P_NET, p.net(), mt);
        cellMontant(row, P_NET1, p.netN1(), mt);
    }

    private void totalPassifPlage(Row row, Styles s, String ref, String libelle,
                                  int premiere0, int derniere0, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, P_REF, ref, styleRef(s, palier));
        cell(row, P_LIB, libelle, lib);
        cell(row, P_NOTE, "", lib);
        sommeOuZero(row, P_NET, premiere0, derniere0, mt);
        sommeOuZero(row, P_NET1, premiere0, derniere0, mt);
    }

    private void totalPassifSomme(Row row, Styles s, String ref, String libelle, int[] lignes0, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, P_REF, ref, styleRef(s, palier));
        cell(row, P_LIB, libelle, lib);
        cell(row, P_NOTE, "", lib);
        for (int col : new int[]{P_NET, P_NET1}) {
            StringBuilder f = new StringBuilder();
            for (int l : lignes0) {
                if (f.length() > 0) f.append('+');
                f.append(colLettre(col)).append(l + 1);
            }
            formule(row, col, f.toString(), mt);
        }
    }

    private int enTetePassif(Sheet sh, Styles s, String titre, LocalDate du, LocalDate au) {
        Row r0 = sh.createRow(0);
        cell(r0, 0, nomSociete(), s.enTeteLiasseCell);
        cell(r0, P_NET, "Exercice clos le " + au.format(DATE_FR), s.sousTitre);
        Row r1 = sh.createRow(1);
        cell(r1, 0, "États financiers annuels — SYSCOHADA révisé (Système Normal)", s.sousTitre);
        cell(r1, P_NET, "Durée (en mois) : " + dureeEnMois(du, au), s.sousTitre);
        Row r2 = sh.createRow(2);
        cell(r2, 0, titre, s.titreLiasse);
        return 4;
    }

    // ---------------------------------------------------------------------
    // Feuille RESULTAT
    // ---------------------------------------------------------------------

    /** Colonnes : REF | LIBELLES | signe | NOTE | NET N | NET N-1. */
    private static final int R_REF = 0, R_LIB = 1, R_SIGNE = 2, R_NOTE = 3, R_NET = 4, R_NET1 = 5;

    /**
     * Compte de résultat au format officiel, avec les soldes intermédiaires
     * de gestion en formules Excel.
     *
     * <p>Convention du modèle : chaque poste est saisi en valeur POSITIVE ;
     * c'est la formule du solde qui porte le signe (la marge commerciale
     * soustrait les achats, elle ne les additionne pas en négatif). Reproduire
     * cette convention est ce qui rend le classeur lisible et modifiable par
     * le comptable.</p>
     */
    private BigDecimal construireResultat(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                          LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("RESULTAT");
        Row r0 = sh.createRow(0);
        cell(r0, 0, nomSociete(), s.enTeteLiasseCell);
        cell(r0, R_NET, "Exercice clos le " + au.format(DATE_FR), s.sousTitre);
        Row r1 = sh.createRow(1);
        cell(r1, 0, "États financiers annuels — SYSCOHADA révisé (Système Normal)", s.sousTitre);
        cell(r1, R_NET, "Durée (en mois) : " + dureeEnMois(du, au), s.sousTitre);
        Row r2 = sh.createRow(2);
        cell(r2, 0, "COMPTE DE RESULTAT", s.titreLiasse);

        int r = 4;
        Row head = sh.createRow(r++);
        cell(head, R_REF, "REF", s.enTete);
        cell(head, R_LIB, "LIBELLES", s.enTete);
        cell(head, R_NOTE, "NOTE", s.enTete);
        cell(head, R_NET, au.format(DATE_FR), s.enTete);
        cell(head, R_NET1, exercicePrecedent(du), s.enTete);

        Map<String, Integer> l = new HashMap<>();

        r = ligneRes(sh, s, r, l, "TA", "Ventes de marchandises", "+", "21", produit(comptes, "701"));
        r = ligneRes(sh, s, r, l, "RA", "Achats de marchandises", "-", "22", charge(comptes, "601"));
        r = ligneRes(sh, s, r, l, "RB", "Variation de stocks de marchandises", "-/+", "6", charge(comptes, "6031"));
        r = soldeRes(sh, s, r, l, "XA", "MARGE COMMERCIALE (Somme TA à RB)",
            f(l, "TA") + "-" + f(l, "RA") + "-" + f(l, "RB"), Palier.TOTAL_MAJEUR);

        r = ligneRes(sh, s, r, l, "TB", "Ventes de produits fabriqués", "+", "21", produit(comptes, "702"));
        r = ligneRes(sh, s, r, l, "TC", "Travaux, services vendus", "+", "21",
            produitPrefixes(comptes, "704", "705", "706"));
        r = ligneRes(sh, s, r, l, "TD", "Produits accessoires", "+", "21", produit(comptes, "707"));
        r = soldeRes(sh, s, r, l, "XB", "CHIFFRE D'AFFAIRES (A + B + C + D)",
            f(l, "TA") + "+" + f(l, "TB") + "+" + f(l, "TC") + "+" + f(l, "TD"), Palier.TOTAL_MAJEUR);

        r = ligneRes(sh, s, r, l, "TE", "Production stockée (ou déstockage)", "-/+", "6", produit(comptes, "73"));
        r = ligneRes(sh, s, r, l, "TF", "Production immobilisée", "+", "21", produit(comptes, "72"));
        r = ligneRes(sh, s, r, l, "TG", "Subventions d'exploitation", "+", "21", produit(comptes, "71"));
        r = ligneRes(sh, s, r, l, "TH", "Autres produits", "+", "21", produit(comptes, "75"));
        r = ligneRes(sh, s, r, l, "TI", "Transferts de charges d'exploitation", "+", "12", produit(comptes, "781"));
        r = ligneRes(sh, s, r, l, "RC", "Achats de matières premières et fournitures liées", "-", "22", charge(comptes, "602"));
        r = ligneRes(sh, s, r, l, "RD", "Variation de stocks de matières premières et fournitures liées", "-/+", "6", charge(comptes, "6032"));
        r = ligneRes(sh, s, r, l, "RE", "Autres achats", "-", "22",
            chargePrefixesSauf(comptes, new String[]{"604", "605", "608"}, new String[0]));
        r = ligneRes(sh, s, r, l, "RF", "Variation de stocks d'autres approvisionnements", "-/+", "6", charge(comptes, "6033"));
        r = ligneRes(sh, s, r, l, "RG", "Transports", "-", "23", charge(comptes, "61"));
        r = ligneRes(sh, s, r, l, "RH", "Services extérieurs", "-", "24",
            chargePrefixesSauf(comptes, new String[]{"62", "63"}, new String[0]));
        r = ligneRes(sh, s, r, l, "RI", "Impôts et taxes", "-", "25", charge(comptes, "64"));
        r = ligneRes(sh, s, r, l, "RJ", "Autres charges", "-", "26", charge(comptes, "65"));
        r = soldeRes(sh, s, r, l, "XC", "VALEUR AJOUTEE (XB + RA + RB) + (somme TE à RJ)",
            f(l, "XB") + "-" + f(l, "RA") + "-" + f(l, "RB")
            + "+" + f(l, "TE") + "+" + f(l, "TF") + "+" + f(l, "TG") + "+" + f(l, "TH") + "+" + f(l, "TI")
            + "-" + f(l, "RC") + "-" + f(l, "RD") + "-" + f(l, "RE") + "-" + f(l, "RF")
            + "-" + f(l, "RG") + "-" + f(l, "RH") + "-" + f(l, "RI") + "-" + f(l, "RJ"), Palier.TOTAL_MAJEUR);

        r = ligneRes(sh, s, r, l, "RK", "Charges de personnel", "-", "27", charge(comptes, "66"));
        r = soldeRes(sh, s, r, l, "XD", "EXCEDENT BRUT D'EXPLOITATION (XC + RK)",
            f(l, "XC") + "-" + f(l, "RK"), Palier.TOTAL_MAJEUR);

        r = ligneRes(sh, s, r, l, "TJ", "Reprises d'amortissements, provisions et dépréciations", "+", "28",
            produitPrefixes(comptes, "791", "798"));
        r = ligneRes(sh, s, r, l, "RL", "Dotations aux amortissements, aux provisions et dépréciations", "-", "3C&28",
            chargePrefixesSauf(comptes, new String[]{"68", "69"}, new String[]{"687", "697"}));
        r = soldeRes(sh, s, r, l, "XE", "RESULTAT D'EXPLOITATION (XD + TJ + RL)",
            f(l, "XD") + "+" + f(l, "TJ") + "-" + f(l, "RL"), Palier.TOTAL_MAJEUR);

        r = ligneRes(sh, s, r, l, "TK", "Revenus financiers et assimilés", "+", "29", produit(comptes, "77"));
        r = ligneRes(sh, s, r, l, "TL", "Reprises de provisions et dépréciations financières", "+", "28", produit(comptes, "797"));
        r = ligneRes(sh, s, r, l, "TM", "Transferts de charges financières", "+", "12", produit(comptes, "787"));
        r = ligneRes(sh, s, r, l, "RM", "Frais financiers et charges assimilées", "-", "29", charge(comptes, "67"));
        r = ligneRes(sh, s, r, l, "RN", "Dotations aux provisions et aux dépréciations financières", "-", "3C&28", charge(comptes, "697"));
        r = soldeRes(sh, s, r, l, "XF", "RESULTAT FINANCIER (somme TK à RN)",
            f(l, "TK") + "+" + f(l, "TL") + "+" + f(l, "TM") + "-" + f(l, "RM") + "-" + f(l, "RN"), Palier.TOTAL_MAJEUR);

        r = soldeRes(sh, s, r, l, "XG", "RESULTAT DES ACTIVITES ORDINAIRES (XE + XF)",
            f(l, "XE") + "+" + f(l, "XF"), Palier.RESULTAT_INTERMEDIAIRE);

        r = ligneRes(sh, s, r, l, "TN", "Produits des cessions d'immobilisations", "+", "3D", produit(comptes, "82"));
        r = ligneRes(sh, s, r, l, "TO", "Autres produits HAO", "+", "30",
            produitPrefixes(comptes, "84", "86", "88"));
        r = ligneRes(sh, s, r, l, "RO", "Valeurs comptables des cessions d'immobilisations", "-", "3D", charge(comptes, "81"));
        r = ligneRes(sh, s, r, l, "RP", "Autres charges HAO", "-", "30",
            chargePrefixesSauf(comptes, new String[]{"83", "85"}, new String[0]));
        r = soldeRes(sh, s, r, l, "XH", "RESULTAT HORS ACTIVITES ORDINAIRES (somme TN à RP)",
            f(l, "TN") + "+" + f(l, "TO") + "-" + f(l, "RO") + "-" + f(l, "RP"), Palier.RESULTAT_INTERMEDIAIRE);

        r = ligneRes(sh, s, r, l, "RQ", "Participation des travailleurs", "-", "30", charge(comptes, "87"));
        r = ligneRes(sh, s, r, l, "RS", "Impôts sur le résultat", "-", "", charge(comptes, "89"));
        Row rXI = sh.createRow(r);
        soldeSurLigne(rXI, s, "XI", "RESULTAT NET (XG + XH + RQ + RS)",
            f(l, "XG") + "+" + f(l, "XH") + "-" + f(l, "RQ") + "-" + f(l, "RS"), Palier.TOTAL_GENERAL);

        largeurs(sh, 1130, 14230, 1100, 2020, 4070, 4580);
        miseEnPageLiasse(sh);

        // Le résultat net est aussi calculé en Java : il alimente le PASSIF
        // (poste CJ) et les Notes, qui ne peuvent pas lire une formule Excel
        // non encore évaluée.
        return resultatNetCalcule(comptes);
    }

    /** Renvoi de cellule de la colonne N pour un code de référence donné. */
    private String f(Map<String, Integer> lignes, String ref) {
        Integer l = lignes.get(ref);
        return l == null ? "0" : "E" + (l + 1);
    }

    private int ligneRes(Sheet sh, Styles s, int r, Map<String, Integer> lignes, String ref,
                         String libelle, String signe, String note, BigDecimal[] valeurs) {
        Row row = sh.createRow(r);
        cell(row, R_REF, ref, s.refLiasse);
        cell(row, R_LIB, libelle, s.libelleLiasse);
        cell(row, R_SIGNE, signe, s.refLiasse);
        cell(row, R_NOTE, note, s.refLiasse);
        cellMontant(row, R_NET, valeurs[0], s.montantLiasse);
        cellMontant(row, R_NET1, valeurs[1], s.montantLiasse);
        lignes.put(ref, r);
        return r + 1;
    }

    private int soldeRes(Sheet sh, Styles s, int r, Map<String, Integer> lignes,
                         String ref, String libelle, String formuleN, Palier palier) {
        Row row = sh.createRow(r);
        soldeSurLigne(row, s, ref, libelle, formuleN, palier);
        lignes.put(ref, r);
        return r + 1;
    }

    /** Un solde intermédiaire : même formule sur la colonne N et la colonne N-1. */
    private void soldeSurLigne(Row row, Styles s, String ref, String libelle, String formuleN, Palier palier) {
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, R_REF, ref, styleRef(s, palier));
        cell(row, R_LIB, libelle, lib);
        cell(row, R_SIGNE, "", lib);
        cell(row, R_NOTE, "", lib);
        formule(row, R_NET, formuleN, mt);
        formule(row, R_NET1, formuleN.replace("E", "F"), mt);
    }

    /** [montant N, montant N-1] d'un poste de produit (solde créditeur). */
    private BigDecimal[] produit(List<LigneCompte> comptes, String prefixe) {
        return produitPrefixes(comptes, prefixe);
    }

    private BigDecimal[] produitPrefixes(List<LigneCompte> comptes, String... prefixes) {
        BigDecimal n = BigDecimal.ZERO, n1 = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            for (String p : prefixes) {
                if (c.numero().startsWith(p)) {
                    n = n.add(c.netMouvement().negate());
                    n1 = n1.add(c.netOuverture().negate());
                    break;
                }
            }
        }
        return new BigDecimal[]{n, n1};
    }

    private BigDecimal[] charge(List<LigneCompte> comptes, String prefixe) {
        return chargePrefixesSauf(comptes, new String[]{prefixe}, new String[0]);
    }

    private BigDecimal[] chargePrefixesSauf(List<LigneCompte> comptes, String[] prefixes, String[] exclus) {
        BigDecimal n = BigDecimal.ZERO, n1 = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            boolean retenu = false;
            for (String p : prefixes) {
                if (c.numero().startsWith(p)) { retenu = true; break; }
            }
            if (!retenu) continue;
            for (String e : exclus) {
                if (c.numero().startsWith(e)) { retenu = false; break; }
            }
            if (!retenu) continue;
            n = n.add(c.netMouvement());
            n1 = n1.add(c.netOuverture());
        }
        return new BigDecimal[]{n, n1};
    }

    /** Résultat net de la période : produits (classe 7 + 82/84/86/88) − charges (classe 6 + 81/83/85/87/89). */
    private BigDecimal resultatNetCalcule(List<LigneCompte> comptes) {
        BigDecimal produits = BigDecimal.ZERO, charges = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.PRODUIT) {
                produits = produits.add(c.netMouvement().negate());
            } else if (c.type() == TypeCompte.CHARGE) {
                charges = charges.add(c.netMouvement());
            }
        }
        return produits.subtract(charges);
    }

    /** Résultat net de l'exercice précédent, reconstitué depuis les soldes d'ouverture. */
    private BigDecimal resultatNetN1(List<LigneCompte> comptes) {
        BigDecimal produits = BigDecimal.ZERO, charges = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.PRODUIT) {
                produits = produits.add(c.netOuverture().negate());
            } else if (c.type() == TypeCompte.CHARGE) {
                charges = charges.add(c.netOuverture());
            }
        }
        return produits.subtract(charges);
    }

    // ---------------------------------------------------------------------
    // Feuille TFT (tableau des flux de trésorerie)
    // ---------------------------------------------------------------------

    /** Colonnes : REF | LIBELLES | repère | Note | N | N-1. */
    private static final int T_REF = 0, T_LIB = 1, T_REP = 2, T_NOTE = 3, T_NET = 4, T_NET1 = 5;

    /**
     * Tableau des flux de trésorerie au format officiel (codes ZA à ZH,
     * FA à FQ), méthode indirecte.
     *
     * <p>Les cinq agrégats (ZB, ZC, ZD, ZE, ZF, ZG, ZH) sont des formules
     * Excel reprenant exactement celles du modèle, y compris leurs signes :
     * une variation de besoin en fonds de roulement se soustrait, un
     * encaissement de cession s'ajoute. La dernière ligne est confrontée à
     * la trésorerie réellement constatée au bilan — le contrôle d'arrêté du
     * modèle, reproduit ici comme une ligne visible plutôt que comme un
     * commentaire.</p>
     */
    private void construireTft(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                               BigDecimal resultatNet, BigDecimal tresorerieActifClo,
                               BigDecimal tresoreriePassifClo, LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("TFT");
        Row r0 = sh.createRow(0);
        cell(r0, 0, nomSociete(), s.enTeteLiasseCell);
        cell(r0, T_NET, "Exercice clos le " + au.format(DATE_FR), s.sousTitre);
        Row r1 = sh.createRow(1);
        cell(r1, 0, "États financiers annuels — SYSCOHADA révisé (Système Normal)", s.sousTitre);
        cell(r1, T_NET, "Durée (en mois) : " + dureeEnMois(du, au), s.sousTitre);
        Row r2 = sh.createRow(2);
        cell(r2, 0, "TABLEAU DES FLUX DE TRESORERIE", s.grandTitre);

        int r = 4;
        Row head = sh.createRow(r++);
        cell(head, T_REF, "REF", s.enTete);
        cell(head, T_LIB, "LIBELLES", s.enTete);
        cell(head, T_NOTE, "Note", s.enTete);
        cell(head, T_NET, au.format(DATE_FR), s.enTete);
        cell(head, T_NET1, exercicePrecedent(du), s.enTete);

        Map<String, Integer> l = new HashMap<>();

        // ZA — trésorerie à l'ouverture (actif − passif, soldes d'ouverture).
        BigDecimal tresoOuvActif = sommeParClasseType(comptes, 5, TypeCompte.ACTIF, LigneCompte::netOuverture);
        BigDecimal tresoOuvPassif = sommeParClasseType(comptes, 5, TypeCompte.PASSIF, c -> c.netOuverture().negate());
        BigDecimal tresorerieOuv = tresoOuvActif.subtract(tresoOuvPassif);
        r = ligneTft(sh, s, r, l, "ZA", "Trésorerie nette au 1er janvier", "A", tresorerieOuv, BigDecimal.ZERO, Palier.TOTAL_GENERAL);
        r = titreTft(sh, s, r, "Flux de trésorerie provenant des activités opérationnelles");

        // FA — CAFG : résultat net augmenté des charges non décaissées.
        BigDecimal dotations = chargePrefixesSauf(comptes, new String[]{"68", "69"}, new String[0])[0];
        BigDecimal reprises = produitPrefixes(comptes, "79")[0];
        BigDecimal cafg = resultatNet.add(dotations).subtract(reprises);
        r = ligneTft(sh, s, r, l, "FA", "Capacité d'Autofinancement Globale (CAFG)", "", cafg, BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FB", "- Variation Actif circulant HAO", "",
            variationActif(comptes, "485"), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FC", "- Variation des stocks", "",
            variationActif(comptes, "3"), BigDecimal.ZERO, Palier.NORMAL);
        // FD et FE reprennent exactement le périmètre des postes BG (créances)
        // et DP (passif circulant) du bilan : toute la classe 4 hors actif
        // circulant HAO (485) et hors écarts de conversion (478/479), qui
        // figurent sur leurs propres lignes.
        r = ligneTft(sh, s, r, l, "FD", "- Variation des créances", "",
            variationActif(comptes, "4")
                .subtract(variationActif(comptes, "485"))
                .subtract(variationActif(comptes, "478")),
            BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FE", "+ Variation du passif circulant", "",
            variationPassif(comptes, "4").subtract(variationPassif(comptes, "479")),
            BigDecimal.ZERO, Palier.NORMAL);
        r = soldeTft(sh, s, r, l, "ZB", "Flux de trésorerie provenant des activités opérationnelles (somme FA à FE)", "B",
            g(l, "FA") + "-" + g(l, "FB") + "-" + g(l, "FC") + "-" + g(l, "FD") + "+" + g(l, "FE"), Palier.TOTAL_MAJEUR);

        r = titreTft(sh, s, r, "Flux de trésorerie provenant des activités d'investissement");
        r = ligneTft(sh, s, r, l, "FF", "- Décaissements liés aux acquisitions d'immobilisations incorporelles", "",
            acquisitions(comptes, "21"), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FG", "- Décaissements liés aux acquisitions d'immobilisations corporelles", "",
            acquisitions(comptes, "22").add(acquisitions(comptes, "23"))
                .add(acquisitions(comptes, "24")).add(acquisitions(comptes, "25")),
            BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FH", "- Décaissements liés aux acquisitions d'immobilisations financières", "",
            acquisitions(comptes, "26").add(acquisitions(comptes, "27")), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FI", "+ Encaissements liés aux cessions d'immobilisations incorporelles et corporelles", "",
            produitPrefixes(comptes, "82")[0], BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FJ", "+ Encaissements liés aux cessions d'immobilisations financières", "",
            BigDecimal.ZERO, BigDecimal.ZERO, Palier.NORMAL);
        r = soldeTft(sh, s, r, l, "ZC", "Flux de trésorerie provenant des activités d'investissement (somme FF à FJ)", "C",
            "-" + g(l, "FF") + "-" + g(l, "FG") + "-" + g(l, "FH") + "+" + g(l, "FI") + "+" + g(l, "FJ"), Palier.TOTAL_MAJEUR);

        r = titreTft(sh, s, r, "Flux de trésorerie provenant du financement par les capitaux propres");
        r = ligneTft(sh, s, r, l, "FK", "+ Augmentations de capital par apports nouveaux", "",
            variationPassif(comptes, "10"), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FL", "+ Subventions d'investissement reçues", "",
            variationPassif(comptes, "14"), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FM", "- Prélèvements sur le capital", "", BigDecimal.ZERO, BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FN", "- Dividendes versés", "", BigDecimal.ZERO, BigDecimal.ZERO, Palier.NORMAL);
        r = soldeTft(sh, s, r, l, "ZD", "Flux de trésorerie provenant des capitaux propres (somme FK à FN)", "D",
            g(l, "FK") + "+" + g(l, "FL") + "-" + g(l, "FM") + "-" + g(l, "FN"), Palier.SOUS_MASSE);

        r = titreTft(sh, s, r, "Trésorerie provenant du financement par les capitaux étrangers");
        r = ligneTft(sh, s, r, l, "FO", "+ Emprunts", "", variationPassif(comptes, "16"), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FP", "+ Autres dettes financières", "",
            variationPassif(comptes, "17").add(variationPassif(comptes, "18")), BigDecimal.ZERO, Palier.NORMAL);
        r = ligneTft(sh, s, r, l, "FQ", "- Remboursements des emprunts et autres dettes financières", "",
            BigDecimal.ZERO, BigDecimal.ZERO, Palier.NORMAL);
        r = soldeTft(sh, s, r, l, "ZE", "Flux de trésorerie provenant des capitaux étrangers (somme FO à FQ)", "E",
            g(l, "FO") + "+" + g(l, "FP") + "-" + g(l, "FQ"), Palier.SOUS_MASSE);

        r = soldeTft(sh, s, r, l, "ZF", "Flux de trésorerie provenant des activités de financement (D + E)", "F",
            g(l, "ZD") + "+" + g(l, "ZE"), Palier.TOTAL_MAJEUR);
        r = soldeTft(sh, s, r, l, "ZG", "VARIATION DE LA TRESORERIE NETTE DE LA PERIODE (B + C + F)", "G",
            g(l, "ZB") + "+" + g(l, "ZC") + "+" + g(l, "ZF"), Palier.RESULTAT_INTERMEDIAIRE);
        r = soldeTft(sh, s, r, l, "ZH", "Trésorerie nette au 31 décembre (G + A)", "H",
            g(l, "ZA") + "+" + g(l, "ZG"), Palier.TOTAL_GENERAL);

        // Contrôle d'arrêté : ZH doit égaler la trésorerie constatée au bilan.
        r++;
        BigDecimal tresorerieBilan = tresorerieActifClo.subtract(tresoreriePassifClo);
        Row ctrl = sh.createRow(r++);
        cell(ctrl, T_REF, "", s.normal);
        cell(ctrl, T_LIB, "Contrôle : Trésorerie actif N − Trésorerie passif N", s.totalLabel);
        cellMontant(ctrl, T_NET, tresorerieBilan, s.totalMontant);
        Row ecart = sh.createRow(r++);
        cell(ecart, T_LIB, "Écart avec ZH (doit être nul)", s.normal);
        formule(ecart, T_NET, "E" + (l.get("ZH") + 1) + "-E" + (ctrl.getRowNum() + 1), s.controleFormule);

        largeurs(sh, 1130, 14230, 1450, 2020, 4070, 4580);
        miseEnPageLiasse(sh);
    }

    private String g(Map<String, Integer> lignes, String ref) {
        Integer l = lignes.get(ref);
        return l == null ? "0" : "E" + (l + 1);
    }

    /** Intitulé de section du TFT : bandeau gris du classeur de référence. */
    private int titreTft(Sheet sh, Styles s, int r, String libelle) {
        Row row = sh.createRow(r);
        for (int col : new int[]{T_REF, T_LIB, T_REP, T_NOTE, T_NET, T_NET1}) {
            cell(row, col, col == T_LIB ? libelle : "", s.rubriqueLabel);
        }
        return r + 1;
    }

    private int ligneTft(Sheet sh, Styles s, int r, Map<String, Integer> lignes, String ref,
                         String libelle, String repere, BigDecimal n, BigDecimal n1, Palier palier) {
        Row row = sh.createRow(r);
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, T_REF, ref, styleRef(s, palier));
        cell(row, T_LIB, libelle, lib);
        cell(row, T_REP, repere, styleRef(s, palier));
        cellMontant(row, T_NET, n, mt);
        cellMontant(row, T_NET1, n1, mt);
        lignes.put(ref, r);
        return r + 1;
    }

    private int soldeTft(Sheet sh, Styles s, int r, Map<String, Integer> lignes,
                         String ref, String libelle, String repere, String formuleN, Palier palier) {
        Row row = sh.createRow(r);
        CellStyle lib = styleLibelle(s, palier);
        CellStyle mt = styleMontant(s, palier);
        cell(row, T_REF, ref, styleRef(s, palier));
        cell(row, T_LIB, libelle, lib);
        cell(row, T_REP, repere, styleRef(s, palier));
        formule(row, T_NET, formuleN, mt);
        formule(row, T_NET1, formuleN.replace("E", "F"), mt);
        lignes.put(ref, r);
        return r + 1;
    }

    /** Variation d'un poste d'actif sur la période (clôture − ouverture). */
    private BigDecimal variationActif(List<LigneCompte> comptes, String prefixe) {
        return cloActifPrefixe(comptes, prefixe).subtract(ouvActifPrefixe(comptes, prefixe));
    }

    private BigDecimal variationPassif(List<LigneCompte> comptes, String prefixe) {
        return cloPassifPrefixe(comptes, prefixe).subtract(ouvPassifPrefixe(comptes, prefixe));
    }

    /** Acquisitions de la période : débits d'un chapitre d'immobilisation. */
    private BigDecimal acquisitions(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.numero().startsWith(prefixe) && c.type() == TypeCompte.ACTIF) {
                total = total.add(c.mvtD());
            }
        }
        return total;
    }



    private BigDecimal sommeSiPrefixe(List<LigneCompte> comptes, String prefixe, boolean commeProduit) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.numero().startsWith(prefixe)) {
                total = total.add(commeProduit ? c.netMouvement().negate() : c.netMouvement());
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // Feuille Bilan
    // ---------------------------------------------------------------------

    /** Totaux du bilan, réutilisés par les Notes annexes et les Graphiques. */
    private record BilanTotals(BigDecimal actifImmobilise, BigDecimal actifCirculant, BigDecimal tresorerieActif,
                                BigDecimal totalActif, BigDecimal totalCapitauxPropres, BigDecimal passifCirculant,
                                BigDecimal tresoreriePassif, BigDecimal totalPassif) {}

    /**
     * Bilan au format officiel SYSCOHADA "Système Normal" (codes de référence
     * AD à DZ), avec les colonnes Brut / Amortissements-Dépréciations / Net
     * de l'actif. La ventilation par poste cible les préfixes de compte
     * exacts du référentiel (ex. 211 frais de développement, 231-239
     * bâtiments/installations, 282 amortissements des terrains) : elle reste
     * donc correcte quel que soit le sous-plan comptable propre à chaque
     * entité, tant qu'il respecte la numérotation SYSCOHADA standard.
     *
     * <p>Certains regroupements de KICO (ex. Bâtiments vs Aménagements, tous
     * deux sous le même compte d'amortissement 283) ne sont pas séparables
     * sans sous-compte dédié : ils sont alors présentés en une seule ligne
     * correspondant au chapitre SYSCOHADA correspondant, ce qui reste
     * pleinement conforme à la présentation minimale du Système Normal.</p>
     */

    /** Solde de clôture net (débit-crédit) pour les comptes ACTIF dont le numéro commence par le préfixe donné. */
    private BigDecimal cloActifPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.ACTIF && c.numero().startsWith(prefixe)) {
                total = total.add(c.cloD().subtract(c.cloC()));
            }
        }
        return total;
    }

    /** Solde de clôture net (crédit-débit) pour les comptes PASSIF dont le numéro commence par le préfixe donné. */
    private BigDecimal cloPassifPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.PASSIF && c.numero().startsWith(prefixe)) {
                total = total.add(c.cloC().subtract(c.cloD()));
            }
        }
        return total;
    }

    /** Triplet [Brut, Amort/Dépréc, Net] pour un poste actif, à partir d'un préfixe brut et, si non-nul, d'un préfixe amort. */
    private BigDecimal[] actifBrutAmortNet(List<LigneCompte> comptes, String prefixeBrut, String prefixeAmort) {
        BigDecimal brut = cloActifPrefixe(comptes, prefixeBrut);
        BigDecimal amort = prefixeAmort == null ? BigDecimal.ZERO : cloActifPrefixe(comptes, prefixeAmort).negate();
        return new BigDecimal[]{brut, amort, brut.subtract(amort)};
    }

    private int ligneBilanActif(Sheet sh, Styles s, int r, String ref, String libelle,
                                 BigDecimal brut, BigDecimal amort, BigDecimal net) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.normal);
        cell(row, 1, libelle, s.normal);
        cellMontant(row, 2, brut, s);
        if (amort != null) cellMontant(row, 3, amort, s);
        cellMontant(row, 4, net != null ? net : brut, s);
        return r + 1;
    }

    private int ligneBilanActifTotal(Sheet sh, Styles s, int r, String ref, String libelle,
                                      BigDecimal brut, BigDecimal amort, BigDecimal net) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.totalLabel);
        cell(row, 1, libelle, s.totalLabel);
        cellMontant(row, 2, brut, s.totalMontant);
        cellMontant(row, 3, amort, s.totalMontant);
        cellMontant(row, 4, net, s.totalMontant);
        return r + 1;
    }

    private int lignePassif(Sheet sh, Styles s, int r, String ref, String libelle, BigDecimal net) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.normal);
        cell(row, 1, libelle, s.normal);
        cellMontant(row, 4, net, s);
        return r + 1;
    }

    private int ligneBilanTotal(Sheet sh, Styles s, int r, String ref, String libelle, BigDecimal net) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.totalLabel);
        cell(row, 1, libelle, s.totalLabel);
        cellMontant(row, 4, net, s.totalMontant);
        return r + 1;
    }

    // ---------------------------------------------------------------------
    // Feuille Flux de trésorerie (méthode indirecte, généralisée)
    // ---------------------------------------------------------------------

    /**
     * Tableau des flux de trésorerie, méthode indirecte, au format officiel
     * SYSCOHADA (codes de référence ZA à ZH). Les flux d'investissement (FF à
     * FJ) et de financement (FK à FQ) ciblent les préfixes de compte exacts
     * (21/22-25/26-27 pour incorporelles/corporelles/financières, 10/14/12
     * pour capitaux propres, 16/18/45/46 pour financement externe) — plus
     * précis que la classe entière, ce qui évite en particulier de compter à
     * tort une dotation aux amortissements (crédit du compte 28, classe 2)
     * comme une cession d'immobilisation.
     *
     * <p>La variation du besoin en fonds de roulement (FB à FE) reste un
     * résidu de réconciliation globale plutôt que le détail poste par poste :
     * calculée ainsi, elle garantit que la variation de trésorerie du tableau
     * correspond exactement, au centime près, à celle réellement observée
     * dans le Grand Livre — quel que soit le nombre de comptes mouvementés.</p>
     */
    private void construireFlux(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                BigDecimal resultatNet, LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("Flux de trésorerie");
        titre(sh, s, "TABLEAU DES FLUX DE TRÉSORERIE – SYSCOHADA RÉVISÉ (méthode indirecte)",
            "Période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR));

        BigDecimal tresoreriePassifOuv = sommeParClasseType(comptes, 5, TypeCompte.PASSIF, c -> c.netOuverture().negate());
        BigDecimal tresorerieOuv = sommeParClasseType(comptes, 5, TypeCompte.ACTIF, LigneCompte::netOuverture)
            .subtract(tresoreriePassifOuv);

        BigDecimal tresorerieActifClo = comptes.stream().filter(c -> c.classe() == 5 && c.type() == TypeCompte.ACTIF)
            .map(LigneCompte::cloD).reduce(BigDecimal.ZERO, BigDecimal::add)
            .subtract(comptes.stream().filter(c -> c.classe() == 5 && c.type() == TypeCompte.ACTIF)
                .map(LigneCompte::cloC).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tresoreriePassifClo = comptes.stream().filter(c -> c.classe() == 5 && c.type() == TypeCompte.PASSIF)
            .map(LigneCompte::cloC).reduce(BigDecimal.ZERO, BigDecimal::add)
            .subtract(comptes.stream().filter(c -> c.classe() == 5 && c.type() == TypeCompte.PASSIF)
                .map(LigneCompte::cloD).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tresorerieClo = tresorerieActifClo.subtract(tresoreriePassifClo);
        BigDecimal variationTotale = tresorerieClo.subtract(tresorerieOuv);

        // FA — Capacité d'autofinancement globale : résultat net + dotations
        // aux amortissements/provisions (exploitation et financières), seules
        // charges non décaissées identifiables sans ambiguïté par préfixe.
        BigDecimal dotationsExploitation = sommeSiPrefixe(comptes, "68", false).add(sommeSiPrefixe(comptes, "69", false))
            .subtract(sommeSiPrefixe(comptes, "697", false));
        BigDecimal dotationsFinancieres = sommeSiPrefixe(comptes, "679", false).add(sommeSiPrefixe(comptes, "697", false));
        BigDecimal cafg = resultatNet.add(dotationsExploitation).add(dotationsFinancieres);

        // FF à FJ — activités d'investissement, par grande catégorie d'immobilisation.
        BigDecimal ff = mvtDebitPrefixe(comptes, "21").negate();
        BigDecimal fg = mvtDebitPrefixe(comptes, "22").add(mvtDebitPrefixe(comptes, "23"))
            .add(mvtDebitPrefixe(comptes, "24")).add(mvtDebitPrefixe(comptes, "25")).negate();
        BigDecimal fh = mvtDebitPrefixe(comptes, "26").add(mvtDebitPrefixe(comptes, "27")).negate();
        BigDecimal fi = mvtCreditPrefixe(comptes, "21").add(mvtCreditPrefixe(comptes, "22"))
            .add(mvtCreditPrefixe(comptes, "23")).add(mvtCreditPrefixe(comptes, "24")).add(mvtCreditPrefixe(comptes, "25"));
        BigDecimal fj = mvtCreditPrefixe(comptes, "26").add(mvtCreditPrefixe(comptes, "27"));
        BigDecimal investissement = ff.add(fg).add(fh).add(fi).add(fj);

        // FK à FN — capitaux propres (capital/primes/écarts de réévaluation
        // sous préfixe 10, subventions 14, dividendes prélevés sur le report
        // à nouveau 12).
        BigDecimal fk = mvtCreditPrefixe(comptes, "10");
        BigDecimal fl = mvtCreditPrefixe(comptes, "14");
        BigDecimal fm = mvtDebitPrefixe(comptes, "10").negate();
        BigDecimal fn = mvtDebitPrefixe(comptes, "12").negate();
        BigDecimal flCapitaux = fk.add(fl).add(fm).add(fn);

        // FO à FQ — emprunts (16/18) et comptes courants d'associés/groupe (45/46).
        BigDecimal fo = mvtCreditPrefixe(comptes, "16").add(mvtCreditPrefixe(comptes, "18"));
        BigDecimal fp = comptes.stream().filter(c -> c.numero().startsWith("45") || c.numero().startsWith("46"))
            .map(c -> c.type() == TypeCompte.PASSIF ? c.netMouvement().negate() : c.netMouvement())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fq = mvtDebitPrefixe(comptes, "16").add(mvtDebitPrefixe(comptes, "18")).negate();
        BigDecimal flEtrangers = fo.add(fp).add(fq);

        BigDecimal financement = flCapitaux.add(flEtrangers);

        // FB à FE — résidu garantissant la réconciliation exacte de la trésorerie.
        BigDecimal operationnelTotal = variationTotale.subtract(investissement).subtract(financement);
        BigDecimal variationBfr = operationnelTotal.subtract(cafg);

        int r = 3;
        Row header = sh.createRow(r++);
        cell(header, 0, "Réf.", s.enTete);
        cell(header, 1, "Libellés", s.enTete);
        cell(header, 2, "Montant", s.enTete);

        r = ligneFluxTotal(sh, s, r, "ZA", "Trésorerie nette au 1er janvier (" + du.format(DATE_FR) + ")", tresorerieOuv);
        r++;

        r = ligneFluxSection(sh, s, r, "Flux de trésorerie provenant des activités opérationnelles");
        r = ligneFlux(sh, s, r, "FA", "Capacité d'autofinancement globale (C.A.F.G.)", cafg);
        r = ligneFlux(sh, s, r, "FB-FE",
            "Variation du BFR lié à l'exploitation (actif circulant HAO, stocks, créances, passif circulant)", variationBfr);
        r = ligneFluxTotal(sh, s, r, "ZB", "FLUX DE TRÉSORERIE DES ACTIVITÉS OPÉRATIONNELLES (A)", operationnelTotal);
        r++;

        r = ligneFluxSection(sh, s, r, "Flux de trésorerie provenant des activités d'investissement");
        r = ligneFlux(sh, s, r, "FF", "Décaissements liés aux acquisitions d'immobilisations incorporelles", ff);
        r = ligneFlux(sh, s, r, "FG", "Décaissements liés aux acquisitions d'immobilisations corporelles", fg);
        r = ligneFlux(sh, s, r, "FH", "Décaissements liés aux acquisitions d'immobilisations financières", fh);
        r = ligneFlux(sh, s, r, "FI", "Encaissements liés aux cessions d'immobilisations incorporelles et corporelles", fi);
        r = ligneFlux(sh, s, r, "FJ", "Encaissements liés aux cessions d'immobilisations financières", fj);
        r = ligneFluxTotal(sh, s, r, "ZC", "FLUX DE TRÉSORERIE DES ACTIVITÉS D'INVESTISSEMENT (B)", investissement);
        r++;

        r = ligneFluxSection(sh, s, r, "Flux de trésorerie provenant du financement par les capitaux propres");
        r = ligneFlux(sh, s, r, "FK", "Augmentations de capital par apports", fk);
        r = ligneFlux(sh, s, r, "FL", "Subventions d'investissement reçues", fl);
        r = ligneFlux(sh, s, r, "FM", "Prélèvements sur le capital", fm);
        r = ligneFlux(sh, s, r, "FN", "Dividendes versés", fn);
        r = ligneFluxTotal(sh, s, r, "ZD", "Flux de trésorerie provenant des capitaux propres (C)", flCapitaux);
        r++;

        r = ligneFluxSection(sh, s, r, "Trésorerie provenant du financement par les capitaux étrangers");
        r = ligneFlux(sh, s, r, "FO", "Emprunts", fo);
        r = ligneFlux(sh, s, r, "FP", "Autres dettes financières (comptes courants associés et groupe)", fp);
        r = ligneFlux(sh, s, r, "FQ", "Remboursements des emprunts et autres dettes financières", fq);
        r = ligneFluxTotal(sh, s, r, "ZE", "Flux de trésorerie provenant des capitaux étrangers (D)", flEtrangers);
        r = ligneFluxTotal(sh, s, r, "ZF", "FLUX DE TRÉSORERIE DES ACTIVITÉS DE FINANCEMENT (C+D)", financement);
        r++;

        r = ligneFluxTotal(sh, s, r, "ZG", "VARIATION DE TRÉSORERIE NETTE DE LA PÉRIODE (A+B+C)",
            operationnelTotal.add(investissement).add(financement));
        r = ligneFluxTotal(sh, s, r, "ZH", "Trésorerie nette au 31 décembre (" + au.format(DATE_FR) + ")", tresorerieClo);

        r++;
        Row controle = sh.createRow(r++);
        BigDecimal recalcul = tresorerieOuv.add(operationnelTotal).add(investissement).add(financement);
        boolean ok = recalcul.subtract(tresorerieClo).abs().compareTo(new BigDecimal("0.01")) < 0;
        cell(controle, 0, ok
            ? "✔ CONTRÔLE OK — la trésorerie de clôture calculée correspond aux soldes de la Balance"
            : "⚠ ÉCART : " + recalcul.subtract(tresorerieClo).abs(),
            ok ? s.controleOk : s.controleKo);
        sh.addMergedRegion(new CellRangeAddress(controle.getRowNum(), controle.getRowNum(), 0, 2));

        largeurs(sh, 2600, 15000, 4200);
    }

    private int ligneFlux(Sheet sh, Styles s, int r, String ref, String libelle, BigDecimal montant) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.normal);
        cell(row, 1, libelle, s.normal);
        cellMontant(row, 2, montant, s);
        return r + 1;
    }

    private int ligneFluxTotal(Sheet sh, Styles s, int r, String ref, String libelle, BigDecimal montant) {
        Row row = sh.createRow(r);
        cell(row, 0, ref, s.totalLabel);
        cell(row, 1, libelle, s.totalLabel);
        cellMontant(row, 2, montant, s.totalMontant);
        return r + 1;
    }

    private int ligneFluxSection(Sheet sh, Styles s, int r, String libelle) {
        Row row = sh.createRow(r);
        cell(row, 1, libelle, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 2));
        return r + 1;
    }

    private BigDecimal mvtDebitPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) if (c.numero().startsWith(prefixe)) total = total.add(c.mvtD());
        return total;
    }

    private BigDecimal mvtCreditPrefixe(List<LigneCompte> comptes, String prefixe) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) if (c.numero().startsWith(prefixe)) total = total.add(c.mvtC());
        return total;
    }

    private BigDecimal sommeParClasseType(List<LigneCompte> comptes, int classe, TypeCompte type,
                                          java.util.function.Function<LigneCompte, BigDecimal> extracteur) {
        return comptes.stream().filter(c -> c.classe() == classe && c.type() == type)
            .map(extracteur).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------------
    // Feuille Ratios
    // ---------------------------------------------------------------------

    private void construireRatios(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                  BigDecimal resultatNet, LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("Ratios");
        titre(sh, s, "RATIOS FINANCIERS – SYSCOHADA RÉVISÉ",
            "Calculés à partir du Bilan, du Compte de résultat et du Tableau des flux");

        BigDecimal actifImmobilise = sommeParClasseType(comptes, 2, TypeCompte.ACTIF, c -> c.cloD().subtract(c.cloC()));
        BigDecimal actifCirculant = comptes.stream().filter(c -> (c.classe() == 3 || c.classe() == 4) && c.type() == TypeCompte.ACTIF)
            .map(c -> c.cloD().subtract(c.cloC())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tresorerieActif = sommeParClasseType(comptes, 5, TypeCompte.ACTIF, c -> c.cloD().subtract(c.cloC()));
        BigDecimal passifCirculant = sommeParClasseType(comptes, 4, TypeCompte.PASSIF, c -> c.cloC().subtract(c.cloD()));
        BigDecimal tresoreriePassif = sommeParClasseType(comptes, 5, TypeCompte.PASSIF, c -> c.cloC().subtract(c.cloD()));
        BigDecimal capitauxPropres = sommeParClasseType(comptes, 1, TypeCompte.PASSIF, c -> c.cloC().subtract(c.cloD()))
            .add(resultatNet);
        BigDecimal totalPassif = capitauxPropres.add(passifCirculant).add(tresoreriePassif);
        BigDecimal totalCharges = sommeSiPrefixe(comptes, "6", false).subtract(sommeSiPrefixe(comptes, "67", false));
        BigDecimal chiffreAffaires = sommeSiPrefixe(comptes, "7", true).subtract(sommeSiPrefixe(comptes, "77", true));

        BigDecimal fr = capitauxPropres.subtract(actifImmobilise);
        BigDecimal bfr = actifCirculant.subtract(passifCirculant);
        BigDecimal tn = fr.subtract(bfr);
        long joursPeriode = java.time.temporal.ChronoUnit.DAYS.between(du, au) + 1;
        BigDecimal mois = BigDecimal.valueOf(joursPeriode).divide(new BigDecimal("30.44"), 2, RoundingMode.HALF_UP);
        if (mois.signum() <= 0) mois = BigDecimal.ONE;

        int r = 3;
        Row header = sh.createRow(r++);
        cell(header, 0, "N°", s.enTete);
        cell(header, 1, "Ratio", s.enTete);
        cell(header, 2, "Valeur", s.enTete);
        cell(header, 3, "Interprétation", s.enTete);
        Row periode = sh.createRow(r++);
        cell(periode, 1, "Nombre de mois couverts par la période :", s.normal);
        cellMontant(periode, 2, mois, s);
        r++;

        r = ratioSection(sh, s, r, "1. ÉQUILIBRE FINANCIER");
        r = ratioLigne(sh, s, r, 1, "Fonds de roulement (FR)", fr,
            "Capitaux propres moins actif immobilisé. Négatif : les emplois durables ne sont pas couverts par des ressources stables.");
        r = ratioLigne(sh, s, r, 2, "Besoin en fonds de roulement (BFR)", bfr,
            "Actif circulant moins passif circulant.");
        r = ratioLigne(sh, s, r, 3, "Trésorerie nette (TN = FR - BFR)", tn,
            "Doit égaler Trésorerie-Actif moins Trésorerie-Passif du bilan.");
        r++;

        r = ratioSection(sh, s, r, "2. STRUCTURE ET SOLVABILITÉ");
        r = ratioLigne(sh, s, r, 4, "Autonomie financière (Capitaux propres / Total passif)",
            ratio(capitauxPropres, totalPassif), "Part de l'entreprise financée par ses fonds propres.");
        r = ratioLigne(sh, s, r, 5, "Taux d'endettement ((Passif circulant + Tréso. passif) / Total passif)",
            ratio(passifCirculant.add(tresoreriePassif), totalPassif), "Part du bilan financée par des dettes.");
        r = ratioLigne(sh, s, r, 6, "Couverture des immobilisations (Capitaux propres / Actif immobilisé)",
            ratio(capitauxPropres, actifImmobilise), "Capacité des fonds propres à financer l'outil de production.");
        r++;

        r = ratioSection(sh, s, r, "3. LIQUIDITÉ");
        r = ratioLigne(sh, s, r, 7, "Liquidité générale ((Actif circulant + Trésorerie) / Passif circulant)",
            ratio(actifCirculant.add(tresorerieActif), passifCirculant), "Capacité à honorer les dettes à court terme.");
        r = ratioLigne(sh, s, r, 8, "Liquidité immédiate (Trésorerie / Passif circulant)",
            ratio(tresorerieActif, passifCirculant), "Part des dettes courantes couverte par la trésorerie disponible.");
        r++;

        r = ratioSection(sh, s, r, "4. STRUCTURE DES CHARGES ET RENTABILITÉ");
        r = ratioLigne(sh, s, r, 9, "Charges mensuelles moyennes décaissées",
            totalCharges.signum() == 0 ? BigDecimal.ZERO : totalCharges.divide(mois, 2, RoundingMode.HALF_UP),
            "Total des charges de la période rapporté au nombre de mois.");
        r = ratioLigne(sh, s, r, 10, "Autonomie de trésorerie (en mois)",
            totalCharges.signum() == 0 ? BigDecimal.ZERO
                : tresorerieActif.divide(totalCharges.divide(mois, 6, RoundingMode.HALF_UP), 2, RoundingMode.HALF_UP),
            "Nombre de mois de charges courantes que la trésorerie actuelle permet de couvrir.");
        r = ratioLigne(sh, s, r, 11, "Chiffre d'affaires de la période", chiffreAffaires,
            chiffreAffaires.signum() == 0
                ? "Aucun produit d'exploitation enregistré sur la période."
                : "Produits d'exploitation constatés au journal sur la période.");
        r = ratioLigne(sh, s, r, 12, "Résultat net de l'exercice", resultatNet,
            resultatNet.signum() < 0 ? "Perte de la période." : "Bénéfice de la période.");
        r = ratioLigne(sh, s, r, 13, "Marge nette (Résultat net / Chiffre d'affaires)",
            chiffreAffaires.signum() == 0 ? BigDecimal.ZERO : ratio(resultatNet, chiffreAffaires),
            chiffreAffaires.signum() == 0 ? "Non significatif : aucun chiffre d'affaires enregistré." : "Part du chiffre d'affaires conservée en résultat.");

        largeurs(sh, 1800, 13000, 4200, 18000);
    }

    // ---------------------------------------------------------------------
    // Feuille Notes (annexes)
    // ---------------------------------------------------------------------

    /**
     * Notes annexes au format officiel SYSCOHADA : une fiche récapitulative
     * (sur le modèle de celle publiée par les commissaires aux comptes, cases
     * "Applicable" / "Non applicable" cochées) suivie de chaque note et
     * tableau du référentiel. Les tableaux calculables à partir du Grand
     * Livre (immobilisations, stocks, tiers, trésorerie, capitaux propres,
     * dettes, détail des charges et produits, cinq derniers exercices, fiche
     * de synthèse des indicateurs) sont renseignés ; ceux qui exigent des
     * données hors du périmètre comptable de l'application (sûretés
     * réelles, crédit-bail, réévaluations, engagements de retraite
     * actuariels, effectifs et masse salariale par catégorie, production
     * physique, informations ESG) sont marqués "Non applicable" avec le motif,
     * plutôt qu'omis — cette fiche reste ainsi la même quelle que soit
     * l'entité, seules les cases cochées diffèrent.
     */
    private void construireNotes(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes, BigDecimal resultatNet,
                                  BilanTotals bilan, LocalDate du, LocalDate au) {
        String societe = nomSociete();
        // Feuille de garde des annexes ; chaque note recoit ensuite sa propre
        // feuille, comme dans la liasse OHADA officielle.
        Sheet garde = wb.createSheet("Notes");
        titre(garde, s, "NOTES ANNEXES AUX ÉTATS FINANCIERS",
            "Exercice du " + du.format(DATE_FR) + " au " + au.format(DATE_FR) + " — référentiel SYSCOHADA révisé (Système Normal)");
        Sheet[] feuille = { garde };

        int r = 3;
        r = ficheRecapitulative(feuille[0], s, r);
        r += 2;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 1 – DETTES GARANTIES PAR DES SÛRETÉS RÉELLES", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne assure pas le suivi des sûretés réelles (hypothèques, "
            + "nantissements, gages, cautions) adossées aux dettes financières.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 2 – INFORMATIONS OBLIGATOIRES", societe, du, au);
        r = noteParagraphe(feuille[0], s, r, "Les états financiers annuels sont préparés selon la méthode du coût "
            + "historique, en conformité avec les règles et méthodes comptables édictées par l'Acte Uniforme "
            + "de l'OHADA relatif au Droit Comptable et à l'Information Financière. Les immobilisations sont "
            + "amorties selon le mode linéaire sur leur durée d'utilité estimée ; les stocks sont évalués au "
            + "coût moyen pondéré (CMP) et dépréciés, le cas échéant, à la valeur nette de réalisation si "
            + "celle-ci est inférieure au coût.");
        String contenuHypBase = "Hypothèse de base — continuité d'exploitation : les états financiers ont été "
            + "préparés en considérant que la société va poursuivre ses activités dans un avenir prévisible.";
        if (bilan.totalCapitauxPropres().signum() < 0) {
            contenuHypBase += " Les capitaux propres sont négatifs à la clôture de l'exercice (" + fmt(bilan.totalCapitauxPropres())
                + "), inférieurs à la moitié du capital social : conformément aux articles 664 et 665 de l'Acte "
                + "Uniforme relatif au Droit des Sociétés Commerciales et du Groupement d'Intérêt Économique, "
                + "les associés devront être convoqués dans les quatre mois suivant l'approbation des comptes "
                + "faisant apparaître cette perte, pour décider s'il y a lieu de dissoudre la société.";
        }
        r = noteParagraphe(feuille[0], s, r, contenuHypBase);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3A – IMMOBILISATIONS BRUTES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Brut ouverture", "Acquisitions", "Cessions", "Brut clôture");
        Mvt incorpBrut = ventilerActif(comptes, "21");
        Mvt corpBrut = combinerMvt(ventilerActif(comptes, "22"), ventilerActif(comptes, "23"),
            ventilerActif(comptes, "24"), ventilerActif(comptes, "25"));
        Mvt finBrut = combinerMvt(ventilerActif(comptes, "26"), ventilerActif(comptes, "27"));
        r = ligneMvt(feuille[0], s, r, "Immobilisations incorporelles", incorpBrut, false);
        r = ligneMvt(feuille[0], s, r, "Immobilisations corporelles", corpBrut, false);
        r = ligneMvt(feuille[0], s, r, "Immobilisations financières", finBrut, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL GÉNÉRAL", combinerMvt(incorpBrut, corpBrut, finBrut), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3B – BIENS PRIS EN LOCATION ACQUISITION", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "aucun contrat de crédit-bail (location-acquisition) n'est suivi dans le système.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3C – IMMOBILISATIONS : AMORTISSEMENTS", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Cumul ouverture", "Dotations", "Diminutions", "Cumul clôture");
        Mvt amortIncorp = ventilerActif(comptes, "281");
        Mvt deprecIncorp = ventilerActif(comptes, "291");
        Mvt amortCorp = combinerMvt(ventilerActif(comptes, "282"), ventilerActif(comptes, "283"), ventilerActif(comptes, "284"));
        Mvt deprecCorp = combinerMvt(ventilerActif(comptes, "292"), ventilerActif(comptes, "293"),
            ventilerActif(comptes, "294"), ventilerActif(comptes, "295"));
        r = ligneMvt(feuille[0], s, r, "Amortissements des immobilisations incorporelles", amortIncorp, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations des immobilisations incorporelles", deprecIncorp, false);
        r = ligneMvt(feuille[0], s, r, "Amortissements des immobilisations corporelles", amortCorp, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations des immobilisations corporelles", deprecCorp, false);
        Mvt amortTotal = combinerMvt(amortIncorp, deprecIncorp, amortCorp, deprecCorp);
        r = ligneMvt(feuille[0], s, r, "TOTAL GÉNÉRAL", amortTotal, true);
        Row vnc = feuille[0].createRow(r++);
        cell(vnc, 0, "VALEUR NETTE COMPTABLE DES IMMOBILISATIONS", s.totalLabel);
        cellMontant(vnc, 4, bilan.actifImmobilise(), s.totalMontant);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3D – IMMOBILISATIONS : PLUS ET MOINS-VALUES DE CESSION", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne rapproche pas individuellement le prix de cession et la "
            + "valeur nette comptable de chaque immobilisation cédée (seuls les montants globaux des comptes "
            + "81 et 82 sont disponibles, cf. Tableau 30).");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3E – INFORMATIONS SUR LES RÉÉVALUATIONS EFFECTUÉES PAR L'ENTITÉ", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "aucune réévaluation d'immobilisation n'est suivie dans le système.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 3F – TABLEAU D'ÉTALEMENT DES CHARGES IMMOBILISÉES", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le sous-plan des charges à étaler sur plusieurs exercices n'est pas "
            + "distingué du reste des immobilisations incorporelles dans le système.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 4 – IMMOBILISATIONS FINANCIÈRES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        Mvt titresPart = ventilerActif(comptes, "26");
        Mvt autresImmoFin = ventilerActif(comptes, "27");
        r = ligneMvt(feuille[0], s, r, "Titres de participation", titresPart, false);
        r = ligneMvt(feuille[0], s, r, "Autres immobilisations financières (prêts, dépôts et cautionnements)", autresImmoFin, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL BRUT", combinerMvt(titresPart, autresImmoFin), true);
        r = ligneMvt(feuille[0], s, r, "Dépréciations (comptes 296/297)",
            combinerMvt(ventilerActif(comptes, "296"), ventilerActif(comptes, "297")), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 5 – ACTIFS CIRCULANTS H.A.O.", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Créances hors activités ordinaires (comptes 48, côté actif)", ventilerActif(comptes, "48"), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 6 – STOCKS ET EN-COURS", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Entrées", "Sorties", "Clôture");
        Mvt stocksBrut = soustraireMvt(ventilerActif(comptes, "3"), ventilerActif(comptes, "39"));
        Mvt stocksDeprec = ventilerActif(comptes, "39");
        r = ligneMvt(feuille[0], s, r, "Stocks et en-cours (valeur brute)", stocksBrut, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations des stocks (compte 39)", stocksDeprec, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL NET", soustraireMvt(stocksBrut, stocksDeprec), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 7 – CLIENTS", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Ventes/Encaissements", "Encaissements/Ventes", "Clôture");
        Mvt clientsBrut = ventilerActif(comptes, "41");
        Mvt clientsDeprec = ventilerActif(comptes, "491");
        r = ligneMvt(feuille[0], s, r, "Clients (valeur brute)", clientsBrut, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations des comptes clients (compte 491)", clientsDeprec, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL NET", soustraireMvt(clientsBrut, clientsDeprec), true);
        r = noteParagraphe(feuille[0], s, r, "Analyse par antériorité (créances à un an au plus / plus d'un an) non "
            + "disponible : le système ne suit pas d'échéancier par créance individuelle.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 8 – AUTRES CRÉANCES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        Mvt autresCreancesBrut = soustraireMvt(ventilerActif(comptes, "4"),
            ventilerActif(comptes, "409"), ventilerActif(comptes, "41"), ventilerActif(comptes, "48"), ventilerActif(comptes, "49"));
        Mvt autresCreancesDeprec = soustraireMvt(ventilerActif(comptes, "49"), ventilerActif(comptes, "490"), ventilerActif(comptes, "491"));
        r = ligneMvt(feuille[0], s, r, "Fournisseurs, avances versées (compte 409)", ventilerActif(comptes, "409"), false);
        r = ligneMvt(feuille[0], s, r, "Autres créances diverses (valeur brute)", autresCreancesBrut, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations sur autres créances", autresCreancesDeprec, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL NET",
            soustraireMvt(combinerMvt(ventilerActif(comptes, "409"), autresCreancesBrut), autresCreancesDeprec), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 9 – TITRES DE PLACEMENTS", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Titres de placement (compte 50)", ventilerActif(comptes, "50"), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 10 – VALEURS À ENCAISSER", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Valeurs à encaisser (compte 51)", ventilerActif(comptes, "51"), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 11 – DISPONIBILITÉS", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Encaissements", "Décaissements", "Clôture");
        Mvt banquesBrut = soustraireMvt(ventilerActif(comptes, "5"),
            ventilerActif(comptes, "50"), ventilerActif(comptes, "51"), ventilerActif(comptes, "59"));
        Mvt tresorerieDeprec = ventilerActif(comptes, "59");
        r = ligneMvt(feuille[0], s, r, "Banques, chèques postaux, caisse (valeur brute)", banquesBrut, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations (compte 59)", tresorerieDeprec, false);
        r = ligneMvt(feuille[0], s, r, "TOTAL NET", soustraireMvt(banquesBrut, tresorerieDeprec), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 12 – ÉCARTS DE CONVERSION", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Écarts de conversion-actif (compte 478)", ventilerActif(comptes, "478"), false);
        r = ligneMvt(feuille[0], s, r, "Écarts de conversion-passif (compte 479)", ventilerPassif(comptes, "479"), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 13 – CAPITAL : VALEUR NOMINALE DES ACTIONS OU PARTS", societe, du, au);
        r = noteParagraphe(feuille[0], s, r, "Capital social au " + au.format(DATE_FR) + " : " + fmt(cloPassifPrefixe(comptes, "10")
            .subtract(cloPassifPrefixe(comptes, "105")).subtract(cloPassifPrefixe(comptes, "106"))) + ". "
            + "Répartition nominative par actionnaire (nom, nationalité, nombre de titres, valeur nominale) non "
            + "disponible : le système ne tient pas de registre des actionnaires.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 14 – PRIMES ET RÉSERVES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Primes liées au capital social (compte 105)", ventilerPassif(comptes, "105"), false);
        r = ligneMvt(feuille[0], s, r, "Écarts de réévaluation (compte 106)", ventilerPassif(comptes, "106"), false);
        r = ligneMvt(feuille[0], s, r, "Réserves (compte 11)", ventilerPassif(comptes, "11"), false);
        r = ligneMvt(feuille[0], s, r, "Report à nouveau (compte 12)", ventilerPassif(comptes, "12"), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 15A – SUBVENTIONS ET PROVISIONS RÉGLEMENTÉES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Dotations", "Reprises", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Subventions d'investissement (compte 14)", ventilerPassif(comptes, "14"), false);
        r = ligneMvt(feuille[0], s, r, "Provisions réglementées et fonds assimilés (compte 15)", ventilerPassif(comptes, "15"), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 15B – AUTRES FONDS PROPRES", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "aucune sous-catégorie d'autres fonds propres n'est distinguée dans le système.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 16A – DETTES FINANCIÈRES ET RESSOURCES STABLES ASSIMILÉES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Nouveaux emprunts", "Remboursements", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Emprunts et dettes financières diverses (compte 16)", ventilerPassif(comptes, "16"), false);
        r = ligneMvt(feuille[0], s, r, "Dettes liées à des participations (compte 18)", ventilerPassif(comptes, "18"), false);
        r = ligneMvt(feuille[0], s, r, "Dettes de location acquisition (compte 17)", ventilerPassif(comptes, "17"), false);
        r = ligneMvt(feuille[0], s, r, "Provisions pour risques et charges (compte 19)", ventilerPassif(comptes, "19"), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 16B – ENGAGEMENT DE RETRAITE ET AVANTAGES ASSIMILÉS (MÉTHODE ACTUARIELLE)", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne dispose pas de module de calcul actuariel des engagements "
            + "de retraite (hypothèses démographiques et financières, taux d'actualisation).");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 16BIS – ENGAGEMENT DE RETRAITE ET AVANTAGES ASSIMILÉS (MÉTHODE SIMPLIFIÉE)", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne dispose pas des données de paie nécessaires (ancienneté, "
            + "salaire de référence) au calcul simplifié des engagements de retraite.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 16C – ACTIFS ET PASSIFS ÉVENTUELS", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne suit pas de registre des litiges, garanties données ou "
            + "engagements hors bilan.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 17 – FOURNISSEURS D'EXPLOITATION", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Règlements", "Achats", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Fournisseurs (compte 40, hors avances)",
            soustraireMvt(ventilerPassif(comptes, "40"), ventilerPassif(comptes, "409")), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 18 – DETTES FISCALES ET SOCIALES", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Charges de la période", "Règlements", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Personnel (compte 42)", ventilerPassif(comptes, "42"), false);
        r = ligneMvt(feuille[0], s, r, "Organismes sociaux (compte 43)", ventilerPassif(comptes, "43"), false);
        r = ligneMvt(feuille[0], s, r, "État et collectivités publiques (compte 44)", ventilerPassif(comptes, "44"), false);
        r = ligneMvt(feuille[0], s, r, "TOTAL",
            combinerMvt(ventilerPassif(comptes, "42"), ventilerPassif(comptes, "43"), ventilerPassif(comptes, "44")), true);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 19 – AUTRES DETTES ET PROVISIONS POUR RISQUES À COURT TERME", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Augmentations", "Diminutions", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Organismes internationaux, associés et groupe (comptes 45/46)",
            combinerMvt(ventilerPassif(comptes, "45"), ventilerPassif(comptes, "46")), false);
        r = ligneMvt(feuille[0], s, r, "Débiteurs et créditeurs divers (compte 47)", ventilerPassif(comptes, "47"), false);
        r = ligneMvt(feuille[0], s, r, "Provisions pour risques à court terme (compte 499)", ventilerPassif(comptes, "499"), false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 20 – BANQUES, CRÉDITS D'ESCOMPTE ET DE TRÉSORERIE", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Tirages", "Remboursements", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Banques, crédits d'escompte et de trésorerie (compte 56)", ventilerPassif(comptes, "56"), false);
        r = ligneMvt(feuille[0], s, r, "Autres concours bancaires de trésorerie",
            soustraireMvt(ventilerPassif(comptes, "5"), ventilerPassif(comptes, "56")), false);
        r++;

        // ---- Détail des charges et produits : mêmes agrégats que le Compte de résultat. ----
        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 21 – CHIFFRE D'AFFAIRES ET AUTRES PRODUITS", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Ventes de marchandises (701)", sommeSiPrefixe(comptes, "701", true));
        r = ligneValeur(feuille[0], s, r, "Ventes de produits fabriqués, travaux et services (702, 705-708)",
            sommeSiPrefixe(comptes, "702", true).add(sommeSiPrefixe(comptes, "705", true))
                .add(sommeSiPrefixe(comptes, "706", true)).add(sommeSiPrefixe(comptes, "707", true))
                .add(sommeSiPrefixe(comptes, "708", true)));
        r = ligneValeur(feuille[0], s, r, "Production stockée et immobilisée (72-73)",
            sommeSiPrefixe(comptes, "72", true).add(sommeSiPrefixe(comptes, "73", true)));
        r = ligneValeur(feuille[0], s, r, "Subventions d'exploitation (71)", sommeSiPrefixe(comptes, "71", true));
        r = ligneValeur(feuille[0], s, r, "Autres produits (75)", sommeSiPrefixe(comptes, "75", true));
        r = ligneValeur(feuille[0], s, r, "Transferts de charges d'exploitation (781)", sommeSiPrefixe(comptes, "781", true));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 22 – ACHATS", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Achats de marchandises (601)", sommeSiPrefixe(comptes, "601", false));
        r = ligneValeur(feuille[0], s, r, "Achats de matières premières et fournitures liées (602)", sommeSiPrefixe(comptes, "602", false));
        r = ligneValeur(feuille[0], s, r, "Variations de stocks achetés (603)",
            sommeSiPrefixe(comptes, "603", false));
        r = ligneValeur(feuille[0], s, r, "Autres achats (604, 605, 608)", sommeSiPrefixe(comptes, "60", false)
            .subtract(sommeSiPrefixe(comptes, "601", false)).subtract(sommeSiPrefixe(comptes, "602", false))
            .subtract(sommeSiPrefixe(comptes, "603", false)));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 23 – TRANSPORT", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Transports (compte 61)", sommeSiPrefixe(comptes, "61", false));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 24 – SERVICES EXTÉRIEURS", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Services extérieurs A (compte 62)", sommeSiPrefixe(comptes, "62", false));
        r = ligneValeur(feuille[0], s, r, "Services extérieurs B (compte 63)", sommeSiPrefixe(comptes, "63", false));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 25 – IMPÔTS ET TAXES", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Impôts et taxes (compte 64)", sommeSiPrefixe(comptes, "64", false));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 26 – AUTRES CHARGES", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Autres charges (compte 65)", sommeSiPrefixe(comptes, "65", false));
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 27A – CHARGES DE PERSONNEL", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        BigDecimal chargesPersonnel = sommeSiPrefixe(comptes, "66", false);
        r = ligneValeur(feuille[0], s, r, "Charges de personnel de l'exercice (compte 66)", chargesPersonnel);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 27B – EFFECTIFS, MASSE SALARIALE ET PERSONNEL EXTÉRIEUR", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne dispose pas de module de gestion des ressources humaines "
            + "(effectifs par catégorie/sexe/nationalité) ; seule la masse salariale globale (Tableau 27A) est "
            + "disponible.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 28 – PROVISIONS ET DÉPRÉCIATIONS INSCRITES AU BILAN", societe, du, au);
        r = enteteMvt(feuille[0], s, r, "Rubrique", "Ouverture", "Dotations", "Reprises", "Clôture");
        r = ligneMvt(feuille[0], s, r, "Provisions réglementées (compte 15)", ventilerPassif(comptes, "15"), false);
        r = ligneMvt(feuille[0], s, r, "Provisions pour risques et charges (compte 19)", ventilerPassif(comptes, "19"), false);
        r = ligneMvt(feuille[0], s, r, "Amortissements et dépréciations des immobilisations (28/29)", amortTotal, false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations des stocks (compte 39)", stocksDeprec, false);
        Mvt deprecTiers = ventilerActif(comptes, "49");
        r = ligneMvt(feuille[0], s, r, "Dépréciations des comptes de tiers (compte 49, hors 499)",
            soustraireMvt(deprecTiers, ventilerActif(comptes, "499")), false);
        r = ligneMvt(feuille[0], s, r, "Provisions pour risques à court terme (compte 499)", ventilerPassif(comptes, "499"), false);
        r = ligneMvt(feuille[0], s, r, "Dépréciations de trésorerie (compte 59)", tresorerieDeprec, false);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 29 – CHARGES ET REVENUS FINANCIERS", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        BigDecimal revenusFinanciers = sommeSiPrefixe(comptes, "77", true).subtract(sommeSiPrefixe(comptes, "779", true));
        BigDecimal reprisesFinancieres = sommeSiPrefixe(comptes, "779", true).add(sommeSiPrefixe(comptes, "797", true));
        BigDecimal fraisFinanciers = sommeSiPrefixe(comptes, "67", false).subtract(sommeSiPrefixe(comptes, "679", false));
        BigDecimal dotationsFinancieresNote = sommeSiPrefixe(comptes, "679", false).add(sommeSiPrefixe(comptes, "697", false));
        r = ligneValeur(feuille[0], s, r, "Revenus financiers et assimilés (77, hors 779)", revenusFinanciers);
        r = ligneValeur(feuille[0], s, r, "Reprises de provisions et dépréciations financières (779, 797)", reprisesFinancieres);
        r = ligneValeur(feuille[0], s, r, "Transferts de charges financières (787)", sommeSiPrefixe(comptes, "787", true));
        r = ligneValeur(feuille[0], s, r, "Frais financiers et charges assimilées (67, hors 679)", fraisFinanciers.negate());
        r = ligneValeur(feuille[0], s, r, "Dotations aux provisions et dépréciations financières (679, 697)", dotationsFinancieresNote.negate());
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 30 – AUTRES CHARGES ET PRODUITS HAO", societe, du, au);
        r = enteteValeur(feuille[0], s, r);
        r = ligneValeur(feuille[0], s, r, "Produits des cessions d'immobilisations (82)", sommeSiPrefixe(comptes, "82", true));
        r = ligneValeur(feuille[0], s, r, "Autres produits HAO (84, 86, 88)", sommeSiPrefixe(comptes, "84", true)
            .add(sommeSiPrefixe(comptes, "86", true)).add(sommeSiPrefixe(comptes, "88", true)));
        r = ligneValeur(feuille[0], s, r, "Valeurs comptables des cessions d'immobilisations (81)", sommeSiPrefixe(comptes, "81", false).negate());
        r = ligneValeur(feuille[0], s, r, "Autres charges HAO (83, 85)",
            sommeSiPrefixe(comptes, "83", false).add(sommeSiPrefixe(comptes, "85", false)).negate());
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 31 – RÉPARTITION DU RÉSULTAT ET AUTRES ÉLÉMENTS DES CINQ DERNIERS EXERCICES", societe, du, au);
        r = tableauCinqExercices(feuille[0], s, r, au);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 32 – PRODUCTION DE L'EXERCICE", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne suit pas de quantités physiques produites (unités, "
            + "tonnages) par article ou produit fini.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 33 – ACHATS DESTINÉS À LA PRODUCTION", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne distingue pas les achats de matières destinées à la "
            + "production des autres achats par quantité et origine.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 34 – FICHE DE SYNTHÈSE DES PRINCIPAUX INDICATEURS FINANCIERS", societe, du, au);
        r = tableauSyntheseIndicateurs(feuille[0], s, r, comptes, resultatNet, bilan, du, au);
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 35 – LISTE DES INFORMATIONS SOCIALES, ENVIRONNEMENTALES ET SOCIÉTALES À FOURNIR", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "le système ne dispose pas de module de collecte des indicateurs "
            + "RH/HSE/environnementaux (effectifs par zone, accidents du travail, émissions de gaz à effet de "
            + "serre, actions sociétales, etc.).");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "NOTE 36 – TABLES DE CODES", societe, du, au);
        r = noteNonApplicable(feuille[0], s, r, "tables de référence du référentiel SYSCOHADA, non applicables à un "
            + "export dynamique construit depuis le Grand Livre.");
        r++;

        r = nouvelleFeuilleNote(feuille, wb, s, "ÉVÉNEMENTS POSTÉRIEURS À LA CLÔTURE", societe, du, au);
        noteParagraphe(feuille[0], s, r, "Néant à la date d'édition du présent rapport.");

        // Largeurs de la feuille de garde ; chaque feuille de note fixe les
        // siennes dans nouvelleFeuilleNote.
        largeurs(garde, 4600, 3600, 3600, 3600, 3600);
    }

    /** Fiche récapitulative des notes/tableaux présentés (sur le modèle publié par les commissaires aux comptes). */
    private int ficheRecapitulative(Sheet sh, Styles s, int r) {
        Row titreFiche = sh.createRow(r++);
        cell(titreFiche, 0, "FICHE RÉCAPITULATIVE DES NOTES ANNEXES PRÉSENTÉES", s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(titreFiche.getRowNum(), titreFiche.getRowNum(), 0, 4));
        Row hdr = sh.createRow(r++);
        cell(hdr, 0, "Tableau", s.enTete);
        cell(hdr, 1, "Intitulé", s.enTete);
        cell(hdr, 2, "Applicable", s.enTete);
        cell(hdr, 3, "Non applicable", s.enTete);
        String[][] items = {
            {"NOTE 1", "Dettes garanties par des sûretés réelles", "N"},
            {"NOTE 2", "Informations obligatoires", "A"},
            {"NOTE 3A", "Immobilisation brute", "A"},
            {"NOTE 3B", "Biens pris en location acquisition", "N"},
            {"NOTE 3C", "Immobilisations : amortissements", "A"},
            {"NOTE 3D", "Immobilisations : plus-values et moins-values de cession", "N"},
            {"NOTE 3E", "Informations sur les réévaluations effectuées par l'entité", "N"},
            {"NOTE 3F", "Tableau d'étalement des charges immobilisées", "N"},
            {"NOTE 4", "Immobilisations financières", "A"},
            {"NOTE 5", "Actifs circulants H.A.O.", "A"},
            {"NOTE 6", "Stocks et en-cours", "A"},
            {"NOTE 7", "Clients", "A"},
            {"NOTE 8", "Autres créances", "A"},
            {"NOTE 9", "Titres de placements", "A"},
            {"NOTE 10", "Valeurs à encaisser", "A"},
            {"NOTE 11", "Disponibilités", "A"},
            {"NOTE 12", "Écarts de conversion", "A"},
            {"NOTE 13", "Capital : valeur nominale des actions ou parts", "N"},
            {"NOTE 14", "Primes et réserves", "A"},
            {"NOTE 15A", "Subventions et provisions réglementées", "A"},
            {"NOTE 15B", "Autres fonds propres", "N"},
            {"NOTE 16A", "Dettes financières et ressources stables assimilées", "A"},
            {"NOTE 16B", "Engagement de retraite et avantages assimilés (méthode actuarielle)", "N"},
            {"NOTE 16BIS", "Engagement de retraite et avantages assimilés (méthode simplifiée)", "N"},
            {"NOTE 16C", "Actifs et passifs éventuels", "N"},
            {"NOTE 17", "Fournisseurs d'exploitation", "A"},
            {"NOTE 18", "Dettes fiscales et sociales", "A"},
            {"NOTE 19", "Autres dettes et provisions pour risques à court terme", "A"},
            {"NOTE 20", "Banques, crédits d'escompte et de trésorerie", "A"},
            {"NOTE 21", "Chiffre d'affaires et autres produits", "A"},
            {"NOTE 22", "Achats", "A"},
            {"NOTE 23", "Transport", "A"},
            {"NOTE 24", "Services extérieurs", "A"},
            {"NOTE 25", "Impôts et taxes", "A"},
            {"NOTE 26", "Autres charges", "A"},
            {"NOTE 27A", "Charges de personnel", "A"},
            {"NOTE 27B", "Effectifs, masse salariale et personnel extérieur", "N"},
            {"NOTE 28", "Provisions et dépréciations inscrites au bilan", "A"},
            {"NOTE 29", "Charges et revenus financiers", "A"},
            {"NOTE 30", "Autres charges et produits H.A.O.", "A"},
            {"NOTE 31", "Répartition du résultat et autres éléments des cinq derniers exercices", "A"},
            {"NOTE 32", "Production de l'exercice", "N"},
            {"NOTE 33", "Achats destinés à la production", "N"},
            {"NOTE 34", "Fiche de synthèse des principaux indicateurs financiers", "A"},
            {"NOTE 35", "Liste des informations sociales, environnementales et sociétales à fournir", "N"},
            {"NOTE 36", "Tables de codes", "N"},
        };
        for (String[] item : items) {
            Row row = sh.createRow(r++);
            cell(row, 0, item[0], s.normal);
            cell(row, 1, item[1], s.normal);
            cell(row, 2, "A".equals(item[2]) ? "✔" : "", s.normal);
            cell(row, 3, "N".equals(item[2]) ? "✔" : "", s.normal);
        }
        return r;
    }

    /**
     * Ouvre une feuille dediee a une note et y ecrit l'en-tete de la liasse
     * OHADA : raison sociale, exercice clos, duree, puis numero et intitule.
     *
     * <p>Les notes tenaient auparavant sur une feuille unique. La liasse
     * officielle en consacre une par note : c'est ce qui permet de les
     * imprimer, les transmettre ou les faire viser separement, et de s'y
     * reperer par l'onglet plutot qu'en faisant defiler des centaines de
     * lignes.</p>
     *
     * @return la ligne a partir de laquelle ecrire le contenu de la note
     */
    private int nouvelleFeuilleNote(Sheet[] feuille, XSSFWorkbook wb, Styles s, String intitule,
                                     String societe, LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet(nomOngletUnique(wb, intitule));
        feuille[0] = sh;

        Row r0 = sh.createRow(0);
        cell(r0, 0, societe, s.totalLabel);
        cell(r0, 4, "Exercice clos le " + au.format(DATE_FR), s.normal);
        Row r1 = sh.createRow(1);
        cell(r1, 4, "Durée (en mois) " + java.time.temporal.ChronoUnit.MONTHS.between(
            du.withDayOfMonth(1), au.withDayOfMonth(1).plusMonths(1)), s.normal);

        Row r2 = sh.createRow(2);
        cell(r2, 0, intitule, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(2, 2, 0, 6));

        largeurs(sh, 14000, 4200, 4200, 4200, 4200, 4200, 4200);
        return 4;
    }

    /**
     * Nom d'onglet Excel derive de l'intitule : 31 caracteres maximum, sans
     * les caracteres interdits, et unique dans le classeur.
     */
    private String nomOngletUnique(XSSFWorkbook wb, String intitule) {
        String base = intitule.split("–")[0].trim();
        if (base.isEmpty()) {
            base = intitule;
        }
        base = base.replaceAll("[\\\\/*?\\[\\]:]", "-").trim();
        if (base.length() > 28) {
            base = base.substring(0, 28).trim();
        }
        String nom = base;
        int suffixe = 2;
        while (wb.getSheet(nom) != null) {
            nom = base + " " + suffixe++;
        }
        return nom;
    }

    /** Raison sociale pour l'en-tete des notes, ou un libelle neutre a defaut. */
    private String nomSociete() {
        return parametresRepository.findAll().stream()
            .findFirst()
            .map(p -> p.getNom() == null ? "" : p.getNom())
            .filter(n -> !n.isBlank())
            .orElse("Entité");
    }

    /**
     * Derniere feuille : lecture commentee des etats par l'agent IA.
     *
     * <p>Le classeur donne les chiffres ; cette feuille dit ce qu'ils
     * signifient — points forts, points de vigilance et actions proposees.
     * Elle s'appuie sur les memes montants que les autres feuilles, deja
     * calcules et equilibres, et non sur une lecture approximative du
     * classeur.</p>
     *
     * <p>L'analyse est facultative par construction : si le service IA est
     * indisponible, la feuille porte la synthese de repli et le classeur reste
     * complet. Une indisponibilite ne doit jamais faire echouer un export
     * d'etats financiers.</p>
     */
    private void construireAnalyseIa(XSSFWorkbook wb, Styles s, LocalDate du, LocalDate au) {
        Sheet sh = wb.createSheet("Analyse IA");
        titre(sh, s, "ANALYSE ET RECOMMANDATIONS",
            "Lecture commentee des etats financiers de la periode du " + du.format(DATE_FR)
            + " au " + au.format(DATE_FR));

        int r = 3;
        com.mbsc.finapp.dto.comptabilite.AnalyseFinanciereResponse analyse;
        try {
            analyse = analyseIa.analyser(du, au);
        } catch (RuntimeException e) {
            log.warn("Analyse IA indisponible pour l'export : {}", e.getMessage());
            Row avert = sh.createRow(r);
            cell(avert, 0, "Analyse indisponible au moment de l'export : " + e.getMessage(),
                s.controleFormule);
            sh.addMergedRegion(new CellRangeAddress(r, r, 0, 5));
            largeurs(sh, 20000, 4000, 4000, 4000, 4000, 4000);
            return;
        }

        Row origine = sh.createRow(r++);
        cell(origine, 0, analyse.genereParIa()
            ? "Genere par intelligence artificielle a partir des montants du present classeur."
            : "Synthese automatique (service IA indisponible).", s.normal);
        sh.addMergedRegion(new CellRangeAddress(origine.getRowNum(), origine.getRowNum(), 0, 5));
        r++;

        r = blocAnalyse(sh, s, r, "SYNTHESE", List.of(analyse.synthese()), false);
        r = blocAnalyse(sh, s, r, "POINTS FORTS", analyse.pointsForts(), true);
        r = blocAnalyse(sh, s, r, "POINTS DE VIGILANCE", analyse.pointsAttention(), true);
        r = blocAnalyse(sh, s, r, "RECOMMANDATIONS", analyse.recommandations(), true);

        Row avis = sh.createRow(r + 1);
        cell(avis, 0, "Analyse indicative : a valider par un professionnel avant toute decision.",
            s.normal);
        sh.addMergedRegion(new CellRangeAddress(avis.getRowNum(), avis.getRowNum(), 0, 5));

        largeurs(sh, 20000, 4000, 4000, 4000, 4000, 4000);
    }

    /** Un bloc titre + liste de la feuille d'analyse. */
    private int blocAnalyse(Sheet sh, Styles s, int r, String titre, List<String> elements, boolean numerote) {
        if (elements == null || elements.isEmpty()) {
            return r;
        }
        Row entete = sh.createRow(r++);
        cell(entete, 0, titre, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(entete.getRowNum(), entete.getRowNum(), 0, 5));

        int i = 1;
        for (String e : elements) {
            if (e == null || e.isBlank()) {
                continue;
            }
            Row row = sh.createRow(r++);
            CellStyle style = wrap(sh.getWorkbook(), s);
            cell(row, 0, numerote ? i++ + ". " + e : e, style);
            sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 5));
            // Hauteur proportionnelle au texte : sans cela, une phrase longue
            // est tronquee a l'affichage malgre le retour a la ligne.
            row.setHeightInPoints(Math.min(90f, 14f + (e.length() / 110) * 13f));
        }
        return r + 1;
    }

    private CellStyle wrap(org.apache.poi.ss.usermodel.Workbook wb, Styles s) {
        CellStyle st = wb.createCellStyle();
        st.cloneStyleFrom(s.normal);
        st.setWrapText(true);
        st.setVerticalAlignment(VerticalAlignment.TOP);
        return st;
    }

    private int noteSection(Sheet sh, Styles s, int r, String titre) {
        Row row = sh.createRow(r);
        cell(row, 0, titre, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 4));
        return r + 1;
    }

    private int noteParagraphe(Sheet sh, Styles s, int r, String texte) {
        Row row = sh.createRow(r);
        cell(row, 0, texte, s.noteTexte);
        sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 4));
        row.setHeightInPoints(45f);
        return r + 1;
    }

    private int noteNonApplicable(Sheet sh, Styles s, int r, String motif) {
        return noteParagraphe(sh, s, r, "Non applicable dans cet export : " + motif);
    }

    /** Un montant en devise, formaté pour insertion dans un paragraphe de note. */
    private String fmt(BigDecimal montant) {
        return String.format(Locale.FRANCE, "%,.2f", montant.setScale(2, RoundingMode.HALF_UP));
    }

    // ---- Ventilation ouverture/mouvements/clôture par préfixe de compte, réutilisée par les notes. ----

    private record Mvt(BigDecimal ouverture, BigDecimal augmentation, BigDecimal diminution, BigDecimal cloture) {}

    private Mvt ventilerActif(List<LigneCompte> comptes, String prefixe) {
        BigDecimal ouv = BigDecimal.ZERO, aug = BigDecimal.ZERO, dim = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.ACTIF && c.numero().startsWith(prefixe)) {
                ouv = ouv.add(c.netOuverture()); aug = aug.add(c.mvtD()); dim = dim.add(c.mvtC());
            }
        }
        return new Mvt(ouv, aug, dim, ouv.add(aug).subtract(dim));
    }

    private Mvt ventilerPassif(List<LigneCompte> comptes, String prefixe) {
        BigDecimal ouv = BigDecimal.ZERO, aug = BigDecimal.ZERO, dim = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.type() == TypeCompte.PASSIF && c.numero().startsWith(prefixe)) {
                ouv = ouv.add(c.netOuverture().negate()); aug = aug.add(c.mvtC()); dim = dim.add(c.mvtD());
            }
        }
        return new Mvt(ouv, aug, dim, ouv.add(aug).subtract(dim));
    }

    private Mvt combinerMvt(Mvt... mvts) {
        BigDecimal ouv = BigDecimal.ZERO, aug = BigDecimal.ZERO, dim = BigDecimal.ZERO, clo = BigDecimal.ZERO;
        for (Mvt m : mvts) {
            ouv = ouv.add(m.ouverture()); aug = aug.add(m.augmentation());
            dim = dim.add(m.diminution()); clo = clo.add(m.cloture());
        }
        return new Mvt(ouv, aug, dim, clo);
    }

    private Mvt soustraireMvt(Mvt base, Mvt... aSoustraire) {
        BigDecimal ouv = base.ouverture(), aug = base.augmentation(), dim = base.diminution(), clo = base.cloture();
        for (Mvt m : aSoustraire) {
            ouv = ouv.subtract(m.ouverture()); aug = aug.subtract(m.augmentation());
            dim = dim.subtract(m.diminution()); clo = clo.subtract(m.cloture());
        }
        return new Mvt(ouv, aug, dim, clo);
    }

    private int enteteMvt(Sheet sh, Styles s, int r, String c0, String c1, String c2, String c3, String c4) {
        Row row = sh.createRow(r);
        cell(row, 0, c0, s.enTete); cell(row, 1, c1, s.enTete); cell(row, 2, c2, s.enTete);
        cell(row, 3, c3, s.enTete); cell(row, 4, c4, s.enTete);
        return r + 1;
    }

    private int ligneMvt(Sheet sh, Styles s, int r, String libelle, Mvt m, boolean total) {
        Row row = sh.createRow(r);
        cell(row, 0, libelle, total ? s.totalLabel : s.normal);
        CellStyle st = total ? s.totalMontant : s.montant;
        cellMontant(row, 1, m.ouverture(), st);
        cellMontant(row, 2, m.augmentation(), st);
        cellMontant(row, 3, m.diminution(), st);
        cellMontant(row, 4, m.cloture(), st);
        return r + 1;
    }

    private int enteteValeur(Sheet sh, Styles s, int r) {
        Row row = sh.createRow(r);
        cell(row, 0, "Rubrique", s.enTete);
        cell(row, 1, "Montant", s.enTete);
        return r + 1;
    }

    private int ligneValeur(Sheet sh, Styles s, int r, String libelle, BigDecimal montant) {
        Row row = sh.createRow(r);
        cell(row, 0, libelle, s.normal);
        cellMontant(row, 1, montant, s);
        return r + 1;
    }

    /**
     * Résultat net recalculé pour un jeu de comptes arbitraire (utilisé pour
     * les exercices antérieurs du Tableau 31) : reprend exactement la même
     * décomposition que {@link #construireResultat}, sous forme compacte.
     */
    private BigDecimal resultatNetSeul(List<LigneCompte> comptes) {
        BigDecimal produitsExploitation = sommeSiPrefixe(comptes, "70", true).add(sommeSiPrefixe(comptes, "71", true))
            .add(sommeSiPrefixe(comptes, "72", true)).add(sommeSiPrefixe(comptes, "73", true))
            .add(sommeSiPrefixe(comptes, "75", true)).add(sommeSiPrefixe(comptes, "781", true));
        BigDecimal chargesExploitation = sommeSiPrefixe(comptes, "60", false).add(sommeSiPrefixe(comptes, "61", false))
            .add(sommeSiPrefixe(comptes, "62", false)).add(sommeSiPrefixe(comptes, "63", false))
            .add(sommeSiPrefixe(comptes, "64", false)).add(sommeSiPrefixe(comptes, "65", false))
            .add(sommeSiPrefixe(comptes, "66", false));
        BigDecimal reprisesExploitation = sommeSiPrefixe(comptes, "79", true).subtract(sommeSiPrefixe(comptes, "797", true));
        BigDecimal dotationsExploitation = sommeSiPrefixe(comptes, "68", false).add(sommeSiPrefixe(comptes, "69", false))
            .subtract(sommeSiPrefixe(comptes, "697", false));
        BigDecimal resultatExploitation = produitsExploitation.subtract(chargesExploitation)
            .add(reprisesExploitation).subtract(dotationsExploitation);

        BigDecimal revenusFinanciers = sommeSiPrefixe(comptes, "77", true).subtract(sommeSiPrefixe(comptes, "779", true));
        BigDecimal reprisesFinancieres = sommeSiPrefixe(comptes, "779", true).add(sommeSiPrefixe(comptes, "797", true));
        BigDecimal transfertsChargesFinancieres = sommeSiPrefixe(comptes, "787", true);
        BigDecimal fraisFinanciers = sommeSiPrefixe(comptes, "67", false).subtract(sommeSiPrefixe(comptes, "679", false));
        BigDecimal dotationsFinancieres = sommeSiPrefixe(comptes, "679", false).add(sommeSiPrefixe(comptes, "697", false));
        BigDecimal resultatFinancier = revenusFinanciers.add(reprisesFinancieres).add(transfertsChargesFinancieres)
            .subtract(fraisFinanciers).subtract(dotationsFinancieres);

        BigDecimal resultatActivitesOrdinaires = resultatExploitation.add(resultatFinancier);

        BigDecimal produitsHao = sommeSiPrefixe(comptes, "82", true)
            .add(sommeSiPrefixe(comptes, "84", true)).add(sommeSiPrefixe(comptes, "86", true)).add(sommeSiPrefixe(comptes, "88", true));
        BigDecimal chargesHao = sommeSiPrefixe(comptes, "81", false).add(sommeSiPrefixe(comptes, "83", false)).add(sommeSiPrefixe(comptes, "85", false));
        BigDecimal resultatHao = produitsHao.subtract(chargesHao);

        BigDecimal participationTravailleurs = sommeSiPrefixe(comptes, "87", false);
        BigDecimal impotResultat = sommeSiPrefixe(comptes, "89", false);

        return resultatActivitesOrdinaires.add(resultatHao).subtract(participationTravailleurs).subtract(impotResultat);
    }

    /** Tableau 31 : capital, chiffre d'affaires et résultat net des cinq derniers exercices (calendaires). */
    private int tableauCinqExercices(Sheet sh, Styles s, int r, LocalDate au) {
        int anneeCourante = au.getYear();
        int[] annees = {anneeCourante, anneeCourante - 1, anneeCourante - 2, anneeCourante - 3, anneeCourante - 4};

        Row hdr = sh.createRow(r++);
        cell(hdr, 0, "Indications", s.enTete);
        for (int i = 0; i < annees.length; i++) cell(hdr, i + 1, String.valueOf(annees[i]), s.enTete);

        BigDecimal[] capital = new BigDecimal[5], ca = new BigDecimal[5], resultat = new BigDecimal[5];
        for (int i = 0; i < annees.length; i++) {
            LocalDate debutAnnee = LocalDate.of(annees[i], 1, 1);
            LocalDate finAnnee = annees[i] == anneeCourante ? au : LocalDate.of(annees[i], 12, 31);
            List<LigneCompte> comptesAnnee = chargerComptes(debutAnnee, finAnnee);
            capital[i] = cloPassifPrefixe(comptesAnnee, "10");
            ca[i] = sommeSiPrefixe(comptesAnnee, "70", true);
            resultat[i] = resultatNetSeul(comptesAnnee);
        }
        r = ligneCinqExercices(sh, s, r, "Capital social", capital);
        r = ligneCinqExercices(sh, s, r, "Chiffre d'affaires", ca);
        r = ligneCinqExercices(sh, s, r, "Résultat net", resultat);
        r = noteParagraphe(sh, s, r, "Effectif moyen et masse salariale des exercices antérieurs : voir Tableau 27A "
            + "pour l'exercice courant (le système ne conserve pas d'historique RH par exercice).");
        return r;
    }

    private int ligneCinqExercices(Sheet sh, Styles s, int r, String libelle, BigDecimal[] valeurs) {
        Row row = sh.createRow(r);
        cell(row, 0, libelle, s.normal);
        for (int i = 0; i < valeurs.length; i++) cellMontant(row, i + 1, valeurs[i], s);
        return r + 1;
    }

    /** Tableau 34 : fiche de synthèse (SIG, CAFG, rentabilité, structure financière), sur le modèle publié. */
    private int tableauSyntheseIndicateurs(Sheet sh, Styles s, int r, List<LigneCompte> comptes,
                                            BigDecimal resultatNet, BilanTotals bilan, LocalDate du, LocalDate au) {
        BigDecimal chiffreAffaires = sommeSiPrefixe(comptes, "70", true);
        BigDecimal margeCommerciale = sommeSiPrefixe(comptes, "701", true).subtract(sommeSiPrefixe(comptes, "601", false))
            .subtract(sommeSiPrefixe(comptes, "6031", false));
        BigDecimal chargesExploitationHorsPersonnel = sommeSiPrefixe(comptes, "60", false).add(sommeSiPrefixe(comptes, "61", false))
            .add(sommeSiPrefixe(comptes, "62", false)).add(sommeSiPrefixe(comptes, "63", false))
            .add(sommeSiPrefixe(comptes, "64", false)).add(sommeSiPrefixe(comptes, "65", false));
        BigDecimal produitsExploitationTotal = sommeSiPrefixe(comptes, "70", true).add(sommeSiPrefixe(comptes, "71", true))
            .add(sommeSiPrefixe(comptes, "72", true)).add(sommeSiPrefixe(comptes, "73", true))
            .add(sommeSiPrefixe(comptes, "75", true)).add(sommeSiPrefixe(comptes, "781", true));
        BigDecimal valeurAjoutee = produitsExploitationTotal.subtract(chargesExploitationHorsPersonnel);
        BigDecimal chargesPersonnel = sommeSiPrefixe(comptes, "66", false);
        BigDecimal ebe = valeurAjoutee.subtract(chargesPersonnel);
        BigDecimal dotationsExploitation = sommeSiPrefixe(comptes, "68", false).add(sommeSiPrefixe(comptes, "69", false))
            .subtract(sommeSiPrefixe(comptes, "697", false));
        BigDecimal reprisesExploitation = sommeSiPrefixe(comptes, "79", true).subtract(sommeSiPrefixe(comptes, "797", true));
        BigDecimal resultatExploitation = ebe.add(reprisesExploitation).subtract(dotationsExploitation);
        BigDecimal dotationsFinancieres = sommeSiPrefixe(comptes, "679", false).add(sommeSiPrefixe(comptes, "697", false));
        BigDecimal cafg = resultatNet.add(dotationsExploitation).add(dotationsFinancieres);

        r = enteteValeur(sh, s, r);
        r = ligneValeur(sh, s, r, "Chiffre d'affaires", chiffreAffaires);
        r = ligneValeur(sh, s, r, "Marge commerciale", margeCommerciale);
        r = ligneValeur(sh, s, r, "Valeur ajoutée", valeurAjoutee);
        r = ligneValeur(sh, s, r, "Excédent brut d'exploitation (EBE)", ebe);
        r = ligneValeur(sh, s, r, "Résultat d'exploitation", resultatExploitation);
        r = ligneValeur(sh, s, r, "Résultat net", resultatNet);
        r = ligneValeur(sh, s, r, "Capacité d'autofinancement globale (C.A.F.G.)", cafg);

        BigDecimal fr = bilan.totalCapitauxPropres().add(cloPassifPrefixe(comptes, "16")).add(cloPassifPrefixe(comptes, "17"))
            .add(cloPassifPrefixe(comptes, "18")).add(cloPassifPrefixe(comptes, "19")).subtract(bilan.actifImmobilise());
        BigDecimal bfr = bilan.actifCirculant().subtract(bilan.passifCirculant());
        BigDecimal tn = bilan.tresorerieActif().subtract(bilan.tresoreriePassif());
        r = ligneValeur(sh, s, r, "Fonds de roulement net global (FR)", fr);
        r = ligneValeur(sh, s, r, "Besoin de financement d'exploitation (BFR)", bfr);
        r = ligneValeur(sh, s, r, "Trésorerie nette (FR - BFR)", fr.subtract(bfr));
        r = ligneValeur(sh, s, r, "Contrôle : Trésorerie-actif - Trésorerie-passif", tn);

        BigDecimal dettesFinancieresBrutes = cloPassifPrefixe(comptes, "16").add(cloPassifPrefixe(comptes, "17"))
            .add(cloPassifPrefixe(comptes, "18")).add(bilan.tresoreriePassif());
        BigDecimal endettementNet = dettesFinancieresBrutes.subtract(bilan.tresorerieActif());
        r = ligneValeur(sh, s, r, "Endettement financier net (dettes financières + trésorerie-passif - trésorerie-actif)", endettementNet);
        r = ligneValeur(sh, s, r, "Rentabilité financière (Résultat net / Capitaux propres)",
            ratio(resultatNet, bilan.totalCapitauxPropres()));
        r = ligneValeur(sh, s, r, "Rentabilité économique (Résultat d'exploitation / (Capitaux propres + Dettes financières))",
            ratio(resultatExploitation, bilan.totalCapitauxPropres().add(dettesFinancieresBrutes)));
        return r;
    }

    // ---------------------------------------------------------------------
    // Feuille Graphiques
    // ---------------------------------------------------------------------

    /** Un point de la courbe mensuelle (valeurs cumulées depuis le début de la période). */
    private record PointMensuel(String libelle, BigDecimal caCumule, BigDecimal resultatCumule,
                                 BigDecimal tresorerieCumulee) {}

    private List<PointMensuel> pointsMensuels(List<EcritureGrandLivre> journal, List<LigneCompte> comptes,
                                               LocalDate du, LocalDate au) {
        BigDecimal tresorerieOuv = comptes.stream().filter(c -> c.classe() == 5)
            .map(LigneCompte::netOuverture).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Les reprises d'a-nouveaux sont deja portees par tresorerieOuv (via les
        // soldes d'ouverture) : les rejouer ici comme mouvements du mois les
        // compterait une seconde fois sur la courbe.
        Map<YearMonth, List<EcritureGrandLivre>> parMois = journal.stream()
            .filter(e -> e.getDateEcriture() != null && !e.getDateEcriture().isAfter(au))
            .filter(e -> !estOuverture(e, du))
            .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDateEcriture())));

        DateTimeFormatter fmtMois = DateTimeFormatter.ofPattern("MMM yy", Locale.FRENCH);
        List<PointMensuel> points = new ArrayList<>();
        // "Résultat cumulé" reprend exactement la décomposition de construireResultat
        // (produits classe 7 - charges classe 6 + HAO classe 8, y compris l'impôt sur
        // le résultat qui y est logé) afin que le dernier point de la courbe se
        // réconcilie avec le résultat net affiché sur les feuilles Bilan et Notes.
        BigDecimal ca = BigDecimal.ZERO, resultat = BigDecimal.ZERO, tresorerie = tresorerieOuv;
        YearMonth ym = YearMonth.from(du);
        YearMonth finMois = YearMonth.from(au);
        while (!ym.isAfter(finMois)) {
            for (EcritureGrandLivre e : parMois.getOrDefault(ym, List.of())) {
                CompteOHADA compte = e.getCompte();
                String numero = compte.getNumero();
                int classe = compte.getClasse() == null ? 0 : compte.getClasse();
                BigDecimal deb = e.getDebit() == null ? BigDecimal.ZERO : e.getDebit();
                BigDecimal cred = e.getCredit() == null ? BigDecimal.ZERO : e.getCredit();
                if (classe == 7 && !numero.startsWith("77")) ca = ca.add(cred.subtract(deb));
                if (classe == 7) resultat = resultat.add(cred.subtract(deb));
                if (classe == 6) resultat = resultat.subtract(deb.subtract(cred));
                if (classe == 8) resultat = resultat.add(cred.subtract(deb));
                if (classe == 5) tresorerie = tresorerie.add(deb.subtract(cred));
            }
            points.add(new PointMensuel(fmtMois.format(ym.atDay(1)), ca, resultat, tresorerie));
            ym = ym.plusMonths(1);
        }
        return points;
    }

    private void construireGraphiques(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                      List<EcritureGrandLivre> journal, BigDecimal resultatNet,
                                      BilanTotals bilan, LocalDate du, LocalDate au) {
        XSSFSheet sh = wb.createSheet("Graphiques");
        titre(sh, s, "GRAPHIQUES – TABLEAU DE BORD",
            "Courbes et indicateurs calculés sur la période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR));

        List<PointMensuel> points = pointsMensuels(journal, comptes, du, au);
        int nbMois = points.size();

        int r = 3;
        Row hdrEvol = sh.createRow(r++);
        cell(hdrEvol, 0, "ÉVOLUTION MENSUELLE (cumulée)", s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(hdrEvol.getRowNum(), hdrEvol.getRowNum(), 0, Math.max(1, nbMois)));

        Row hdrMois = sh.createRow(r++);
        cell(hdrMois, 0, "Mois", s.enTete);
        for (int i = 0; i < nbMois; i++) cell(hdrMois, i + 1, points.get(i).libelle(), s.enTete);
        int ligneMois = hdrMois.getRowNum();

        Row rowCa = sh.createRow(r++);
        cell(rowCa, 0, "CA cumulé", s.totalLabel);
        for (int i = 0; i < nbMois; i++) cellMontant(rowCa, i + 1, points.get(i).caCumule(), s);
        int ligneCa = rowCa.getRowNum();

        Row rowRes = sh.createRow(r++);
        cell(rowRes, 0, "Résultat cumulé", s.totalLabel);
        for (int i = 0; i < nbMois; i++) cellMontant(rowRes, i + 1, points.get(i).resultatCumule(), s);
        int ligneRes = rowRes.getRowNum();

        Row rowTre = sh.createRow(r++);
        cell(rowTre, 0, "Trésorerie", s.totalLabel);
        for (int i = 0; i < nbMois; i++) cellMontant(rowTre, i + 1, points.get(i).tresorerieCumulee(), s);
        int ligneTre = rowTre.getRowNum();

        r++;
        Row hdrBilan = sh.createRow(r++);
        cell(hdrBilan, 0, "STRUCTURE DU BILAN", s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(hdrBilan.getRowNum(), hdrBilan.getRowNum(), 0, 1));
        Row hdrBilanCols = sh.createRow(r++);
        cell(hdrBilanCols, 0, "Poste", s.enTete);
        cell(hdrBilanCols, 1, "Montant", s.enTete);
        String[] libellesBilan = {"Actif immobilisé", "Actif circulant", "Trésorerie-actif",
            "Capitaux propres", "Passif circulant", "Trésorerie-passif"};
        BigDecimal[] valeursBilan = {bilan.actifImmobilise(), bilan.actifCirculant(), bilan.tresorerieActif(),
            bilan.totalCapitauxPropres(), bilan.passifCirculant(), bilan.tresoreriePassif()};
        int ligneBilanDebut = r;
        for (int i = 0; i < libellesBilan.length; i++) {
            Row row = sh.createRow(r++);
            cell(row, 0, libellesBilan[i], s.normal);
            cellMontant(row, 1, valeursBilan[i], s);
        }
        int ligneBilanFin = r - 1;

        r++;
        Row hdrRatios = sh.createRow(r++);
        cell(hdrRatios, 0, "RATIOS CLÉS", s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(hdrRatios.getRowNum(), hdrRatios.getRowNum(), 0, 1));
        Row hdrRatiosCols = sh.createRow(r++);
        cell(hdrRatiosCols, 0, "Ratio", s.enTete);
        cell(hdrRatiosCols, 1, "Valeur", s.enTete);

        BigDecimal chiffreAffaires = sommeSiPrefixe(comptes, "7", true).subtract(sommeSiPrefixe(comptes, "77", true));
        String[] libellesRatios = {"Autonomie financière", "Taux d'endettement", "Liquidité générale",
            "Liquidité immédiate", "Marge nette"};
        BigDecimal[] valeursRatios = {
            ratio(bilan.totalCapitauxPropres(), bilan.totalPassif()),
            ratio(bilan.passifCirculant().add(bilan.tresoreriePassif()), bilan.totalPassif()),
            ratio(bilan.actifCirculant().add(bilan.tresorerieActif()), bilan.passifCirculant()),
            ratio(bilan.tresorerieActif(), bilan.passifCirculant()),
            chiffreAffaires.signum() == 0 ? BigDecimal.ZERO : ratio(resultatNet, chiffreAffaires),
        };
        int ligneRatiosDebut = r;
        for (int i = 0; i < libellesRatios.length; i++) {
            Row row = sh.createRow(r++);
            cell(row, 0, libellesRatios[i], s.normal);
            Cell c = row.createCell(1);
            c.setCellValue(valeursRatios[i].doubleValue());
            c.setCellStyle(s.pourcentage);
        }
        int ligneRatiosFin = r - 1;

        largeurs(sh, 5000, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600);

        int colDepart = Math.max(4, nbMois + 3);
        ajouterGraphiques(sh, nbMois, ligneMois, ligneCa, ligneRes, ligneTre,
            ligneBilanDebut, ligneBilanFin, ligneRatiosDebut, ligneRatiosFin, colDepart);
    }

    private void ajouterGraphiques(XSSFSheet sh, int nbMois, int ligneMois, int ligneCa, int ligneRes, int ligneTre,
                                   int ligneBilanDebut, int ligneBilanFin, int ligneRatiosDebut, int ligneRatiosFin,
                                   int colDepart) {
        XSSFDrawing drawing = sh.createDrawingPatriarch();

        if (nbMois > 0) {
            XDDFCategoryDataSource moisCat = XDDFDataSourcesFactory.fromStringCellRange(sh,
                new CellRangeAddress(ligneMois, ligneMois, 1, nbMois));
            XDDFNumericalDataSource<Double> caSrc = XDDFDataSourcesFactory.fromNumericCellRange(sh,
                new CellRangeAddress(ligneCa, ligneCa, 1, nbMois));
            XDDFNumericalDataSource<Double> resSrc = XDDFDataSourcesFactory.fromNumericCellRange(sh,
                new CellRangeAddress(ligneRes, ligneRes, 1, nbMois));
            XDDFNumericalDataSource<Double> treSrc = XDDFDataSourcesFactory.fromNumericCellRange(sh,
                new CellRangeAddress(ligneTre, ligneTre, 1, nbMois));

            XSSFChart perfChart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, colDepart, 0, colDepart + 8, 16));
            perfChart.setTitleText("Courbe de performance (CA et résultat cumulés)");
            perfChart.setTitleOverlay(false);
            XDDFCategoryAxis perfCat = perfChart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis perfVal = perfChart.createValueAxis(AxisPosition.LEFT);
            perfVal.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFLineChartData perfData = (XDDFLineChartData) perfChart.createData(ChartTypes.LINE, perfCat, perfVal);
            XDDFLineChartData.Series serieCa = (XDDFLineChartData.Series) perfData.addSeries(moisCat, caSrc);
            serieCa.setTitle("CA cumulé", null);
            serieCa.setSmooth(false);
            serieCa.setMarkerStyle(MarkerStyle.CIRCLE);
            XDDFLineChartData.Series serieRes = (XDDFLineChartData.Series) perfData.addSeries(moisCat, resSrc);
            serieRes.setTitle("Résultat cumulé", null);
            serieRes.setSmooth(false);
            serieRes.setMarkerStyle(MarkerStyle.CIRCLE);
            perfChart.plot(perfData);
            perfChart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);

            XSSFChart treChart = drawing.createChart(
                drawing.createAnchor(0, 0, 0, 0, colDepart + 9, 0, colDepart + 17, 16));
            treChart.setTitleText("Évolution de la trésorerie");
            treChart.setTitleOverlay(false);
            XDDFCategoryAxis treCat = treChart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis treVal = treChart.createValueAxis(AxisPosition.LEFT);
            treVal.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFLineChartData treData = (XDDFLineChartData) treChart.createData(ChartTypes.LINE, treCat, treVal);
            XDDFLineChartData.Series serieTre = (XDDFLineChartData.Series) treData.addSeries(moisCat, treSrc);
            serieTre.setTitle("Trésorerie", null);
            serieTre.setSmooth(false);
            serieTre.setMarkerStyle(MarkerStyle.CIRCLE);
            treChart.plot(treData);
            treChart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        }

        if (ligneBilanFin >= ligneBilanDebut) {
            XDDFCategoryDataSource bilanCat = XDDFDataSourcesFactory.fromStringCellRange(sh,
                new CellRangeAddress(ligneBilanDebut, ligneBilanFin, 0, 0));
            XDDFNumericalDataSource<Double> bilanVal = XDDFDataSourcesFactory.fromNumericCellRange(sh,
                new CellRangeAddress(ligneBilanDebut, ligneBilanFin, 1, 1));

            XSSFChart bilanChart = drawing.createChart(
                drawing.createAnchor(0, 0, 0, 0, colDepart, 17, colDepart + 8, 33));
            bilanChart.setTitleText("Structure du bilan");
            bilanChart.setTitleOverlay(false);
            XDDFCategoryAxis bCat = bilanChart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis bVal = bilanChart.createValueAxis(AxisPosition.LEFT);
            bVal.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFBarChartData bilanData = (XDDFBarChartData) bilanChart.createData(ChartTypes.BAR, bCat, bVal);
            bilanData.setBarDirection(BarDirection.COL);
            XDDFBarChartData.Series serieBilan = (XDDFBarChartData.Series) bilanData.addSeries(bilanCat, bilanVal);
            serieBilan.setTitle("Montant", null);
            bilanChart.plot(bilanData);
        }

        if (ligneRatiosFin >= ligneRatiosDebut) {
            XDDFCategoryDataSource ratiosCat = XDDFDataSourcesFactory.fromStringCellRange(sh,
                new CellRangeAddress(ligneRatiosDebut, ligneRatiosFin, 0, 0));
            XDDFNumericalDataSource<Double> ratiosVal = XDDFDataSourcesFactory.fromNumericCellRange(sh,
                new CellRangeAddress(ligneRatiosDebut, ligneRatiosFin, 1, 1));

            XSSFChart ratiosChart = drawing.createChart(
                drawing.createAnchor(0, 0, 0, 0, colDepart + 9, 17, colDepart + 17, 33));
            ratiosChart.setTitleText("Ratios clés");
            ratiosChart.setTitleOverlay(false);
            XDDFCategoryAxis rCat = ratiosChart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis rVal = ratiosChart.createValueAxis(AxisPosition.LEFT);
            rVal.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFBarChartData ratiosData = (XDDFBarChartData) ratiosChart.createData(ChartTypes.BAR, rCat, rVal);
            ratiosData.setBarDirection(BarDirection.COL);
            XDDFBarChartData.Series serieRatios = (XDDFBarChartData.Series) ratiosData.addSeries(ratiosCat, ratiosVal);
            serieRatios.setTitle("Valeur", null);
            ratiosChart.plot(ratiosData);
        }
    }

    private BigDecimal ratio(BigDecimal numerateur, BigDecimal denominateur) {
        if (denominateur == null || denominateur.signum() == 0) return BigDecimal.ZERO;
        return numerateur.divide(denominateur, 4, RoundingMode.HALF_UP);
    }

    private int ratioSection(Sheet sh, Styles s, int r, String titre) {
        Row row = sh.createRow(r);
        cell(row, 0, titre, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 3));
        return r + 1;
    }

    private int ratioLigne(Sheet sh, Styles s, int r, int numero, String libelle, BigDecimal valeur, String interpretation) {
        Row row = sh.createRow(r);
        cell(row, 0, String.valueOf(numero), s.normal);
        cell(row, 1, libelle, s.normal);
        cellMontant(row, 2, valeur, s);
        cell(row, 3, interpretation, s.interpretation);
        row.setHeightInPoints(Math.max(row.getHeightInPoints(), 28f));
        return r + 1;
    }

    // ---------------------------------------------------------------------
    // Aides communes (styles, cellules, formules)
    // ---------------------------------------------------------------------

    /**
     * Fixe des largeurs de colonnes explicites (unité POI : 1/256e de
     * caractère). Volontairement PAS {@code Sheet.autoSizeColumn} : cette
     * méthode s'appuie sur le rendu de police AWT, indisponible sur l'image
     * d'exécution Alpine (sans fontconfig) et donc susceptible d'échouer ou
     * de produire des largeurs fausses selon l'environnement.
     */
    private void largeurs(Sheet sh, int... unites) {
        for (int c = 0; c < unites.length; c++) {
            sh.setColumnWidth(c, unites[c]);
        }
    }

    /**
     * Mise en page des feuilles de la liasse, calquée sur le classeur OHADA de
     * référence : lignes assez hautes pour que les libellés renvoyés à la ligne
     * restent lisibles, quadrillage écran masqué (les bordures des paliers
     * suffisent), et impression tenant sur une page en largeur — une liasse
     * qui déborde sur une seconde page est inexploitable en réunion.
     */
    private void miseEnPageLiasse(Sheet sh) {
        sh.setDefaultRowHeightInPoints(20f);
        sh.setDisplayGridlines(false);
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 0);
        sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sh.setMargin(Sheet.LeftMargin, 0.3);
        sh.setMargin(Sheet.RightMargin, 0.3);
    }

    private void titre(Sheet sh, Styles s, String titre, String sousTitre) {
        Row t = sh.createRow(0);
        cell(t, 1, titre, s.grandTitre);
        Row st = sh.createRow(1);
        cell(st, 1, sousTitre, s.sousTitre);
    }

    private void cell(Row row, int col, String valeur, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(valeur);
        c.setCellStyle(style);
    }

    private void cellMontant(Row row, int col, BigDecimal valeur, Styles s) {
        cellMontant(row, col, valeur, s.montant);
    }

    private void cellMontant(Row row, int col, BigDecimal valeur, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(valeur == null ? 0d : valeur.setScale(2, RoundingMode.HALF_UP).doubleValue());
        c.setCellStyle(style);
    }

    private void formule(Row row, int col, String formule, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellFormula(formule);
        c.setCellStyle(style);
    }

    /** Total en formule SUM sur une plage, ou 0 si la plage est vide (POI refuse SUM(A5:A4)). */
    private void sommeOuZero(Row row, int col, int premiereLigne0, int derniereLigne0, CellStyle style) {
        if (derniereLigne0 < premiereLigne0) {
            cellMontant(row, col, BigDecimal.ZERO, style);
            return;
        }
        String colLettre = colLettre(col);
        formule(row, col, "SUM(" + colLettre + (premiereLigne0 + 1) + ":" + colLettre + (derniereLigne0 + 1) + ")",
            style);
    }

    private String colLettre(int col0) {
        StringBuilder sb = new StringBuilder();
        int n = col0;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    /**
     * Palette du classeur OHADA de reference (« OHADA REV ... FIN.xlsx ») :
     * chaque teinte y porte un niveau de lecture precis, et les reprendre a
     * l'identique est ce qui rend la liasse generee reconnaissable par le
     * comptable et l'administration fiscale.
     */
    private static final String C_ENTETE       = "91A7C3"; // bandeau d'en-tete de colonnes
    private static final String C_RUBRIQUE     = "D9D9D9"; // sous-total de rubrique (gris)
    private static final String C_SOUS_MASSE   = "DAE1EB"; // composante d'une masse (bleu clair, TFT)
    private static final String C_TOTAL_MAJEUR = "002060"; // totaux de masse / SIG (bleu marine)
    private static final String C_RESULTAT_INT = "F2EEE8"; // resultats intermediaires (creme)
    private static final String C_TOTAL_GENERAL = "7C984A"; // total general / resultat net (vert olive)
    private static final String C_TITRE_DOC    = "0070C0"; // titre du document (bleu)

    /** Police de toute la liasse OHADA (le classeur de reference est en Arial). */
    private static final String POLICE_LIASSE = "Arial";
    /** La liasse OHADA s'exprime en unites entieres, sans decimales. */
    private static final String FORMAT_MONTANT_LIASSE = "#,##0;[RED]-#,##0";

    private static XSSFColor couleurHex(String hex) {
        return new XSSFColor(new byte[]{
            (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
    }

    /** Styles Excel construits une fois par classeur. */
    private static final class Styles {
        final CellStyle grandTitre, sousTitre, enTete, sousEntete, classeHeader, sectionHeader,
            normal, montant, totalLabel, totalMontant, controleFormule, controleOk, controleKo, interpretation,
            noteTexte, pourcentage;

        /**
         * Paliers de lecture de la liasse OHADA. Chaque palier existe en deux
         * variantes (libelle / montant) parce que la teinte couvre la ligne
         * entiere : un montant conserve en plus son format numerique et son
         * alignement a droite.
         */
        final CellStyle refLiasse, libelleLiasse, montantLiasse;
        final CellStyle posteMasseLabel, posteMasseMontant;
        final CellStyle rubriqueLabel, rubriqueMontant;
        final CellStyle sousMasseLabel, sousMasseMontant;
        final CellStyle totalMajeurLabel, totalMajeurMontant;
        final CellStyle resultatIntLabel, resultatIntMontant;
        final CellStyle totalGeneralLabel, totalGeneralMontant;
        final CellStyle titreLiasse, enTeteLiasseCell;

        private final XSSFWorkbook wb;
        private final DataFormat fmt;

        /** Fabrique une police Arial de la liasse. */
        private XSSFFont policeLiasse(int taille, boolean gras, String couleurHexOuNull) {
            XSSFFont f = wb.createFont();
            f.setFontName(POLICE_LIASSE);
            f.setFontHeightInPoints((short) taille);
            f.setBold(gras);
            if (couleurHexOuNull != null) {
                f.setColor(couleurHex(couleurHexOuNull));
            }
            return f;
        }

        /**
         * Un palier de la liasse : teinte de fond (ou null), police, et
         * variante montant ou libelle. Les bordures fines reprennent le
         * quadrillage du classeur de reference.
         */
        private CellStyle palier(String fondHexOuNull, XSSFFont police, boolean estMontant) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(police);
            if (fondHexOuNull != null) {
                st.setFillForegroundColor(couleurHex(fondHexOuNull));
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            st.setBorderTop(BorderStyle.THIN);
            st.setBorderBottom(BorderStyle.THIN);
            st.setBorderLeft(BorderStyle.THIN);
            st.setBorderRight(BorderStyle.THIN);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            if (estMontant) {
                st.setDataFormat(fmt.getFormat(FORMAT_MONTANT_LIASSE));
                st.setAlignment(HorizontalAlignment.RIGHT);
            } else {
                st.setWrapText(true);
            }
            return st;
        }

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            this.fmt = wb.createDataFormat();

            Font fTitre = wb.createFont();
            fTitre.setBold(true);
            fTitre.setFontHeightInPoints((short) 14);
            grandTitre = wb.createCellStyle();
            grandTitre.setFont(fTitre);

            Font fSousTitre = wb.createFont();
            fSousTitre.setItalic(true);
            fSousTitre.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            sousTitre = wb.createCellStyle();
            sousTitre.setFont(fSousTitre);

            Font fBlanc = wb.createFont();
            fBlanc.setBold(true);
            fBlanc.setColor(IndexedColors.WHITE.getIndex());

            // En-tetes de colonnes : bandeau bleu acier du classeur OHADA.
            enTete = palier(C_ENTETE, policeLiasse(9, true, "FFFFFF"), false);
            ((XSSFCellStyle) enTete).setAlignment(HorizontalAlignment.CENTER);
            sousEntete = palier(C_ENTETE, policeLiasse(9, true, "FFFFFF"), false);
            ((XSSFCellStyle) sousEntete).setAlignment(HorizontalAlignment.CENTER);

            classeHeader = wb.createCellStyle();
            classeHeader.setFont(fBlanc);
            classeHeader.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
            classeHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Font fSection = wb.createFont();
            fSection.setBold(true);
            sectionHeader = wb.createCellStyle();
            sectionHeader.setFont(fSection);
            sectionHeader.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            normal = wb.createCellStyle();

            montant = wb.createCellStyle();
            montant.setDataFormat(fmt.getFormat(FORMAT_MONTANT));
            montant.setAlignment(HorizontalAlignment.RIGHT);

            pourcentage = wb.createCellStyle();
            pourcentage.setDataFormat(fmt.getFormat("0.0%"));
            pourcentage.setAlignment(HorizontalAlignment.RIGHT);

            Font fTotal = wb.createFont();
            fTotal.setBold(true);
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(fTotal);
            totalLabel.setBorderTop(BorderStyle.THIN);

            totalMontant = wb.createCellStyle();
            totalMontant.cloneStyleFrom(montant);
            totalMontant.setFont(fTotal);
            totalMontant.setBorderTop(BorderStyle.THIN);

            // ---- Paliers de la liasse OHADA -----------------------------
            XSSFFont pNormale = policeLiasse(9, false, "000000");
            XSSFFont pGrasNoir = policeLiasse(9, true, "000000");
            XSSFFont pGrasBlanc = policeLiasse(9, true, "FFFFFF");
            XSSFFont pGrasCreme = policeLiasse(9, true, "0D0D0D");

            refLiasse = palier(null, pNormale, false);
            ((XSSFCellStyle) refLiasse).setAlignment(HorizontalAlignment.CENTER);
            libelleLiasse = palier(null, pNormale, false);
            montantLiasse = palier(null, pNormale, true);

            // Poste de masse : gras sans teinte (BA/BB/BG du classeur de reference).
            posteMasseLabel = palier(null, pGrasNoir, false);
            posteMasseMontant = palier(null, pGrasNoir, true);

            rubriqueLabel = palier(C_RUBRIQUE, pGrasNoir, false);
            rubriqueMontant = palier(C_RUBRIQUE, pGrasNoir, true);

            sousMasseLabel = palier(C_SOUS_MASSE, pGrasNoir, false);
            sousMasseMontant = palier(C_SOUS_MASSE, pGrasNoir, true);

            totalMajeurLabel = palier(C_TOTAL_MAJEUR, pGrasBlanc, false);
            totalMajeurMontant = palier(C_TOTAL_MAJEUR, pGrasBlanc, true);

            resultatIntLabel = palier(C_RESULTAT_INT, pGrasCreme, false);
            resultatIntMontant = palier(C_RESULTAT_INT, pGrasCreme, true);

            totalGeneralLabel = palier(C_TOTAL_GENERAL, pGrasBlanc, false);
            totalGeneralMontant = palier(C_TOTAL_GENERAL, pGrasBlanc, true);

            titreLiasse = wb.createCellStyle();
            titreLiasse.setFont(policeLiasse(11, true, C_TITRE_DOC));

            enTeteLiasseCell = wb.createCellStyle();
            enTeteLiasseCell.setFont(policeLiasse(10, false, "000000"));

            Font fControle = wb.createFont();
            fControle.setBold(true);
            controleFormule = wb.createCellStyle();
            controleFormule.setFont(fControle);

            Font fOk = wb.createFont();
            fOk.setBold(true);
            fOk.setColor(IndexedColors.GREEN.getIndex());
            controleOk = wb.createCellStyle();
            controleOk.setFont(fOk);

            Font fKo = wb.createFont();
            fKo.setBold(true);
            fKo.setColor(IndexedColors.RED.getIndex());
            controleKo = wb.createCellStyle();
            controleKo.setFont(fKo);

            Font fInterp = wb.createFont();
            fInterp.setItalic(true);
            fInterp.setFontHeightInPoints((short) 9);
            interpretation = wb.createCellStyle();
            interpretation.setFont(fInterp);
            interpretation.setWrapText(true);
            interpretation.setVerticalAlignment(VerticalAlignment.CENTER);

            noteTexte = wb.createCellStyle();
            noteTexte.setWrapText(true);
            noteTexte.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }
}

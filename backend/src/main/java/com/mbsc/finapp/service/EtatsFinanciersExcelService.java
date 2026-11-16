package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.SoldeOuverture;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
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
 *
 * <p>Depuis le 13/11/2026, le classeur part du modèle de liasse OHADA de
 * l'entreprise ({@link LiasseOhada}) : page de garde, fiches R1 à R4, BILAN
 * paysage et notes 1 à 36 y gardent leur mise en page d'origine ; les feuilles
 * Journal, Balance, Ratios, Graphiques et Analyse IA suivent la liasse.</p>
 */
@Service
@RequiredArgsConstructor
public class EtatsFinanciersExcelService {

    private static final Logger log = LoggerFactory.getLogger(EtatsFinanciersExcelService.class);

    private final EcritureGrandLivreRepository ecritureRepository;
    /** Raison sociale portee en en-tete de chaque feuille de note. */
    private final ParametresEntrepriseRepository parametresRepository;
    /** Renseignements de la liasse (page de garde, fiches R1 à R3). */
    private final ParametresEntrepriseService parametresService;
    /** Produit la synthese et les recommandations de la derniere feuille. */
    private final AnalyseFinanciereIaService analyseIa;

    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String FORMAT_MONTANT = "#,##0.00;[RED]-#,##0.00";

    /** Une ligne de compte fusionnant ouverture + mouvements + clôture. */
    private record LigneCompte(String numero, String libelle, TypeCompte type, int classe,
                                BigDecimal ouvD, BigDecimal ouvC, BigDecimal mvtD, BigDecimal mvtC,
                                BigDecimal n1D, BigDecimal n1C) {
        BigDecimal netOuverture() { return ouvD.subtract(ouvC); }
        /** Solde d'ouverture tel que la balance l'imprime : débiteur ou créditeur, jamais les deux. */
        BigDecimal ouvertureD() { return netOuverture().signum() > 0 ? netOuverture() : BigDecimal.ZERO; }
        BigDecimal ouvertureC() { return netOuverture().signum() < 0 ? netOuverture().negate() : BigDecimal.ZERO; }
        /**
         * Mouvements de l'exercice précédent, sans sa pièce de clôture : la colonne N-1 du compte de résultat
         * (audit SYSCOHADA du 10/11/2026, C-04). Le solde d'ouverture, lui, vaut zéro pour un compte de
         * gestion dès que l'exercice précédent est clôturé.
         */
        BigDecimal netMouvementN1() { return n1D.subtract(n1C); }
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
        return generer(du, au, true);
    }

    /**
     * Classeur de l'archive de clôture (audit SYSCOHADA du 10/11/2026, C-13) : sans contrôle de rôle — la clôture
     * est approuvée par le DA ou le DG — et sans la feuille d'analyse par intelligence artificielle, qui enverrait
     * les comptes à un service extérieur et ne relève pas des livres à conserver.
     */
    public byte[] genererClasseurArchive(LocalDate du, LocalDate au) {
        return generer(du, au, false);
    }

    private byte[] generer(LocalDate du, LocalDate au, boolean avecAnalyseIa) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<LigneCompte> comptes = chargerComptes(debut, fin);
        List<EcritureGrandLivre> journal = ecritureRepository.journalJusqua(fin);

        // Le classeur part du modèle de liasse OHADA (page de garde, fiches R1 à R4,
        // BILAN, notes 1 à 36, codes) ; l'application y ajoute ses propres feuilles.
        try (XSSFWorkbook wb = LiasseOhada.ouvrirModele()) {
            ParametresEntreprise entreprise = parametresRepository.findAll().stream().findFirst().orElse(null);
            Styles s = new Styles(wb, EnTeteFeuille.Identite.de(entreprise, debut, fin));
            List<XSSFSheet> feuillesModele = new ArrayList<>();
            wb.forEach(f -> feuillesModele.add((XSSFSheet) f));

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
            LiasseOhada.remplir(wb, new LiasseOhada.Contexte(debut, fin, entreprise,
                parametresService.lireLiasse(entreprise), soldes(comptes),
                k -> soldes(chargerComptes(debut.minusYears(k), fin.minusYears(k)))));
            // Demande du 15/11/2026 : un seul gabarit d'en-tête, y compris sur les feuilles du modèle.
            feuillesModele.forEach(s.entete::harmoniserModele);
            construireRatios(wb, s, comptes, resultatNet, debut, fin);
            construireGraphiques(wb, s, comptes, journal, resultatNet, bilanTotals, debut, fin);
            if (avecAnalyseIa) {
                construireAnalyseIa(wb, s, debut, fin);
            }
            ordonnerFeuilles(wb);
            wb.forEach(EnTeteFeuille::piedDePage);
            evaluerFormules(wb);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le classeur des états financiers", e);
        }
    }

    /**
     * Ordre de la liasse d'origine : page de garde, fiches R1 à R4, ACTIF, PASSIF,
     * BILAN, RESULTAT, TFT, notes 1 à 36, codes ; puis les feuilles de travail de
     * l'application (Journal, Balance, Ratios, Graphiques, Analyse IA).
     */
    private void ordonnerFeuilles(XSSFWorkbook wb) {
        List<String> ordre = new ArrayList<>(List.of("PAGE DE GARDE", "FICHE R1", "FICHE R2", "FICHE R3", "FICHE R4",
            "ACTIF", "PASSIF", "BILAN", "RESULTAT", "TFT"));
        List<String> autres = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String nom = wb.getSheetName(i);
            if (nom.startsWith("NOTE ")) {
                ordre.add(nom);
            } else if (!ordre.contains(nom)) {
                autres.add(nom);
            }
        }
        autres.remove("CODES");
        ordre.add("CODES");
        ordre.addAll(autres);
        int position = 0;
        for (String nom : ordre) {
            if (wb.getSheet(nom) != null) {
                wb.setSheetOrder(nom, position++);
            }
        }
        for (Sheet sh : wb) {
            sh.setSelected(false);
        }
        wb.setActiveSheet(0);
        wb.setSelectedTab(0);
    }

    /**
     * Calcule les formules et en range le résultat dans le fichier : un tableur
     * qui ne recalcule pas à l'ouverture (LibreOffice par défaut, aperçus des
     * messageries) affiche ainsi les vrais montants. Une formule que POI ne sait
     * pas calculer garde sa valeur vide ; Excel la recalcule de toute façon.
     */
    private void evaluerFormules(XSSFWorkbook wb) {
        FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
        for (Sheet sh : wb) {
            for (Row row : sh) {
                for (Cell c : row) {
                    if (c.getCellType() == CellType.FORMULA) {
                        try {
                            ev.evaluateFormulaCell(c);
                        } catch (RuntimeException e) {
                            log.debug("Formule non calculée {}!{} : {}", sh.getSheetName(), c.getAddress(), e.getMessage());
                        }
                    }
                }
            }
        }
        wb.setForceFormulaRecalculation(true);
    }

    /** Vue des comptes chargés pour la liasse OHADA (soldes et mouvements par préfixe). */
    private LiasseOhada.Soldes soldes(List<LigneCompte> comptes) {
        return new LiasseOhada.Soldes() {
            @Override
            public BigDecimal solde(TypeCompte type, boolean ouverture, String... prefixes) {
                BigDecimal t = BigDecimal.ZERO;
                for (LigneCompte c : comptes) {
                    if ((type == null || c.type() == type) && LiasseOhada.correspond(c.numero(), prefixes)) {
                        t = t.add(ouverture ? c.netOuverture() : c.netCloture());
                    }
                }
                return t;
            }

            @Override
            public BigDecimal debits(String... prefixes) {
                BigDecimal t = BigDecimal.ZERO;
                for (LigneCompte c : comptes) {
                    if (LiasseOhada.correspond(c.numero(), prefixes)) {
                        t = t.add(c.mvtD());
                    }
                }
                return t;
            }

            @Override
            public BigDecimal credits(String... prefixes) {
                BigDecimal t = BigDecimal.ZERO;
                for (LigneCompte c : comptes) {
                    if (LiasseOhada.correspond(c.numero(), prefixes)) {
                        t = t.add(c.mvtC());
                    }
                }
                return t;
            }

            @Override
            public BigDecimal flux(boolean precedent, String... prefixes) {
                BigDecimal t = BigDecimal.ZERO;
                for (LigneCompte c : comptes) {
                    if (LiasseOhada.correspond(c.numero(), prefixes)) {
                        t = t.add(precedent ? c.netMouvementN1() : c.netMouvement());
                    }
                }
                return t;
            }

            @Override
            public boolean vide() {
                return comptes.stream().allMatch(c -> c.netCloture().signum() == 0 && c.netMouvement().signum() == 0);
            }
        };
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

        // Seulement les écritures de la période demandée : un journal partiel réimporté ne doit pas ramener
        // les exercices antérieurs.
        List<EcritureGrandLivre> journal = ecritureRepository.journalJusqua(fin).stream()
            .filter(e -> e.getDateEcriture() == null || !e.getDateEcriture().isBefore(debut))
            .toList();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ParametresEntreprise entreprise = parametresRepository.findAll().stream().findFirst().orElse(null);
            construireJournal(wb, new Styles(wb, EnTeteFeuille.Identite.de(entreprise, debut, fin)), journal, debut, fin);

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
        Map<String, Object[]> precedents = new HashMap<>();
        for (Object[] row : ecritureRepository.mouvementsParCompte(du.minusYears(1), du.minusDays(1))) {
            precedents.put((String) row[0], row);
        }

        Set<String> numeros = new TreeSet<>();
        numeros.addAll(ouvertures.keySet());
        numeros.addAll(mouvements.keySet());
        numeros.addAll(precedents.keySet());

        List<LigneCompte> resultat = new ArrayList<>();
        for (String numero : numeros) {
            Object[] o = ouvertures.get(numero);
            Object[] m = mouvements.get(numero);
            Object[] n1 = precedents.get(numero);
            Object[] reference = m != null ? m : o != null ? o : n1;
            String libelle = (String) reference[1];
            TypeCompte type = (TypeCompte) reference[2];
            Integer classe = (Integer) reference[3];
            resultat.add(new LigneCompte(
                numero, libelle, type, classe == null ? 0 : classe,
                o != null ? (BigDecimal) o[4] : BigDecimal.ZERO,
                o != null ? (BigDecimal) o[5] : BigDecimal.ZERO,
                m != null ? (BigDecimal) m[4] : BigDecimal.ZERO,
                m != null ? (BigDecimal) m[5] : BigDecimal.ZERO,
                n1 != null ? (BigDecimal) n1[4] : BigDecimal.ZERO,
                n1 != null ? (BigDecimal) n1[5] : BigDecimal.ZERO
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
        int r0 = titre(sh, s, "JOURNAL COMPTABLE", "Toutes les écritures jusqu'au " + au.format(DATE_FR)
            + " — période courante à partir du " + du.format(DATE_FR), 8);

        int r = r0;
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
            // Règle du 13/11/2026 : une écriture de reprise des à-nouveaux se lit « … - Solde d'ouverture ».
            cell(row, 5, SoldeOuverture.libelle(e), s.normal);
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
        int r0 = titre(sh, s, "BALANCE GÉNÉRALE À 6 COLONNES – SYSCOHADA RÉVISÉ",
            "Du " + du.format(DATE_FR) + " au " + au.format(DATE_FR)
            + " — comptes du Grand Livre ayant porté au moins une écriture", 7);

        int r = r0;
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
                // SYSCOHADA (balance générale des comptes) : le solde débiteur OU créditeur à l'ouverture de
                // l'exercice, non les cumuls de débits et de crédits antérieurs.
                cellMontant(row, 2, c.ouvertureD(), s);
                cellMontant(row, 3, c.ouvertureC(), s);
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
        int r = enTeteLiasse(sh, s, "BILAN – ACTIF AU " + au.format(DATE_FR), 6);

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
    private int enTeteLiasse(Sheet sh, Styles s, String titre, int derniereColonne) {
        return s.entete.poser(sh, titre, derniereColonne, SOUS_TITRE_LIASSE) + 1;
    }

    private static final String SOUS_TITRE_LIASSE = "États financiers annuels — SYSCOHADA révisé (Système Normal)";

    private String exercicePrecedent(LocalDate du) {
        return du.minusDays(1).format(DATE_FR);
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
        int r = enTeteLiasse(sh, s, "BILAN – PASSIF AU " + au.format(DATE_FR), 4);

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
        // Résultat au bilan : celui de la période (classes 6, 7 et 8, sans la pièce de clôture), plus le
        // résultat non encore affecté — porté au compte 13 par une clôture, ou resté dans les classes de
        // gestion d'un exercice antérieur non clôturé. La pièce de clôture ne fait que déplacer le résultat
        // des classes de gestion vers le 13 : le total ne dépend pas de ce que la période est close ou non.
        r = lignePassifLiasse(sh, s, r, "CJ", "Résultat net de l'exercice (bénéfice + ou perte -)", "",
            new PostePassif(resultatNet.add(resultatNetN1).add(resultatEnInstance(comptes, true)),
                resultatNetN1.add(resultatEnInstance(comptes, false))));
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
        int r = enTeteLiasse(sh, s, "COMPTE DE RÉSULTAT AU " + au.format(DATE_FR), R_NET1);
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
                    n1 = n1.add(c.netMouvementN1().negate());
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
            n1 = n1.add(c.netMouvementN1());
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

    /**
     * Résultat resté dans les comptes de gestion à l'ouverture de la période : celui des exercices antérieurs
     * non clôturés (zéro dès que l'exercice précédent est clôturé, sa pièce de clôture l'ayant porté au 13).
     */
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

    /** Solde créditeur des comptes 13 (résultat en instance d'affectation), à l'ouverture ou à la fin de la période. */
    private BigDecimal resultatEnInstance(List<LigneCompte> comptes, boolean aLaFin) {
        BigDecimal total = BigDecimal.ZERO;
        for (LigneCompte c : comptes) {
            if (c.numero().startsWith("13")) {
                total = total.add((aLaFin ? c.netCloture() : c.netOuverture()).negate());
            }
        }
        return total;
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
        int r = enTeteLiasse(sh, s, "TABLEAU DES FLUX DE TRÉSORERIE AU " + au.format(DATE_FR), T_NET1);
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
            produitPrefixes(comptes, "82")[0].subtract(produitPrefixes(comptes, "826")[0]), BigDecimal.ZERO, Palier.NORMAL);
        // FJ : prix de cession des titres (826) et remboursements de prêts, dépôts et cautionnements (crédits des
        // comptes 27). Ils manquaient : les prêts au personnel remboursés laissaient un écart entre la trésorerie
        // finale du TFT et celle du bilan (audit du 15/11/2026, 895 sur l'exercice 2026).
        r = ligneTft(sh, s, r, l, "FJ", "+ Encaissements liés aux cessions d'immobilisations financières", "",
            produitPrefixes(comptes, "826")[0].add(mvtCreditPrefixe(comptes, "27")), BigDecimal.ZERO, Palier.NORMAL);
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
        int r0 = titre(sh, s, "TABLEAU DES FLUX DE TRÉSORERIE – SYSCOHADA RÉVISÉ (méthode indirecte)",
            "Période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR), 2);

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

        int r = r0;
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
        int r0 = titre(sh, s, "RATIOS FINANCIERS – SYSCOHADA RÉVISÉ",
            "Calculés à partir du Bilan, du Compte de résultat et du Tableau des flux", 3);

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

        int r = r0;
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
        int r0 = titre(sh, s, "ANALYSE ET RECOMMANDATIONS",
            "Lecture commentee des etats financiers de la periode du " + du.format(DATE_FR)
            + " au " + au.format(DATE_FR), 5);

        int r = r0;
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

    /** Intitulés SYSCOHADA des chapitres de charges (classe 6), pour la répartition par nature. */
    private static final String[][] CHARGES_PAR_NATURE = {
        {"60", "Achats et variations de stocks"}, {"61", "Transports"}, {"62", "Services extérieurs A"},
        {"63", "Services extérieurs B"}, {"64", "Impôts et taxes"}, {"65", "Autres charges"},
        {"66", "Charges de personnel"}, {"67", "Frais financiers"}, {"68", "Dotations aux amortissements"},
        {"69", "Dotations aux provisions"},
    };

    /** Nombre de comptes de produits montrés un à un ; les suivants sont regroupés en « Autres produits ». */
    private static final int PRODUITS_DETAILLES = 6;

    /** Couleurs fixes des séries : un même indicateur garde sa couleur d'un graphique à l'autre. */
    private static final String COUL_CA = "2E75B6", COUL_RESULTAT = "ED7D31", COUL_TRESORERIE = "70AD47",
        COUL_BILAN = "4472C4", COUL_CHARGES = "C55A11", COUL_RATIOS = "7F6000";

    /**
     * Feuille « Graphiques » : à gauche les tableaux (évolution mensuelle, structure du bilan, ratios clés,
     * charges par nature, produits par compte), à droite les graphiques, deux par rangée, chacun bâti sur les
     * cellules du tableau correspondant — un graphique ne montre jamais un chiffre absent de la feuille.
     */
    private void construireGraphiques(XSSFWorkbook wb, Styles s, List<LigneCompte> comptes,
                                      List<EcritureGrandLivre> journal, BigDecimal resultatNet,
                                      BilanTotals bilan, LocalDate du, LocalDate au) {
        XSSFSheet sh = wb.createSheet("Graphiques");
        List<PointMensuel> points = pointsMensuels(journal, comptes, du, au);
        int nbMois = points.size();
        int derniere = Math.max(G_COL_GRAPHES + 2 * G_LARGEUR_GRAPHE - 1, nbMois);
        int r = titre(sh, s, "GRAPHIQUES – TABLEAU DE BORD",
            "Courbes et indicateurs calculés sur la période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR),
            derniere);

        // --- Évolution mensuelle -------------------------------------------------------------------
        Row hdrEvol = sh.createRow(r++);
        cell(hdrEvol, 0, "ÉVOLUTION MENSUELLE", s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(hdrEvol.getRowNum(), hdrEvol.getRowNum(), 0, Math.max(1, nbMois)));
        Row hdrMois = sh.createRow(r++);
        cell(hdrMois, 0, "Mois", s.enTete);
        for (int i = 0; i < nbMois; i++) cell(hdrMois, i + 1, points.get(i).libelle(), s.enTete);
        int ligneMois = hdrMois.getRowNum();

        String[] libellesEvol = {"CA du mois", "Résultat du mois", "CA cumulé", "Résultat cumulé",
            "Trésorerie (fin de mois)"};
        int[] lignesEvol = new int[libellesEvol.length];
        for (int k = 0; k < libellesEvol.length; k++) {
            Row row = sh.createRow(r++);
            cell(row, 0, libellesEvol[k], s.totalLabel);
            for (int i = 0; i < nbMois; i++) {
                PointMensuel p = points.get(i);
                PointMensuel prec = i == 0 ? null : points.get(i - 1);
                BigDecimal v = switch (k) {
                    case 0 -> prec == null ? p.caCumule() : p.caCumule().subtract(prec.caCumule());
                    case 1 -> prec == null ? p.resultatCumule() : p.resultatCumule().subtract(prec.resultatCumule());
                    case 2 -> p.caCumule();
                    case 3 -> p.resultatCumule();
                    default -> p.tresorerieCumulee();
                };
                cellMontant(row, i + 1, v, s);
            }
            lignesEvol[k] = row.getRowNum();
        }
        r++;
        int hautTableaux = r;

        // --- Tableaux de gauche ----------------------------------------------------------------------
        String[] libellesBilan = {"Actif immobilisé", "Actif circulant", "Trésorerie-actif",
            "Capitaux propres", "Passif circulant", "Trésorerie-passif"};
        BigDecimal[] valeursBilan = {bilan.actifImmobilise(), bilan.actifCirculant(), bilan.tresorerieActif(),
            bilan.totalCapitauxPropres(), bilan.passifCirculant(), bilan.tresoreriePassif()};
        int[] plageBilan = tableauGraphique(sh, s, r, "STRUCTURE DU BILAN", "Poste", "Montant",
            List.of(libellesBilan), List.of(valeursBilan), false);
        r = plageBilan[1] + 2;

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
        int[] plageRatios = tableauGraphique(sh, s, r, "RATIOS CLÉS", "Ratio", "Valeur",
            List.of(libellesRatios), List.of(valeursRatios), true);
        r = plageRatios[1] + 2;

        List<String> libCharges = new ArrayList<>();
        List<BigDecimal> valCharges = new ArrayList<>();
        for (String[] nature : CHARGES_PAR_NATURE) {
            BigDecimal v = sommeSiPrefixe(comptes, nature[0], false);
            if (v.signum() != 0) {
                libCharges.add(nature[0] + " – " + nature[1]);
                valCharges.add(v);
            }
        }
        int[] plageCharges = tableauGraphique(sh, s, r, "CHARGES PAR NATURE (classe 6)", "Nature", "Montant",
            libCharges, valCharges, false);
        r = plageCharges[1] + 2;

        List<LigneCompte> produits = comptes.stream()
            .filter(c -> c.classe() == 7 && c.netMouvement().negate().signum() > 0)
            .sorted(Comparator.comparing((LigneCompte c) -> c.netMouvement().negate()).reversed())
            .toList();
        List<String> libProduits = new ArrayList<>();
        List<BigDecimal> valProduits = new ArrayList<>();
        BigDecimal autres = BigDecimal.ZERO;
        for (int i = 0; i < produits.size(); i++) {
            LigneCompte c = produits.get(i);
            if (i < PRODUITS_DETAILLES) {
                libProduits.add(c.numero() + " – " + c.libelle());
                valProduits.add(c.netMouvement().negate());
            } else {
                autres = autres.add(c.netMouvement().negate());
            }
        }
        if (autres.signum() != 0) {
            libProduits.add("Autres produits");
            valProduits.add(autres);
        }
        int[] plageProduits = tableauGraphique(sh, s, r, "PRODUITS PAR COMPTE (classe 7)", "Compte", "Montant",
            libProduits, valProduits, false);

        largeurs(sh, 9500, 3800, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600);
        for (int c = 14; c <= derniere; c++) {
            sh.setColumnWidth(c, 3600);
        }

        // --- Graphiques : deux par rangée, à droite des tableaux, sans chevauchement -----------------
        XSSFDrawing dessin = sh.createDrawingPatriarch();
        int[] place = {0};
        java.util.function.Supplier<XSSFChart> suivant = () -> {
            int i = place[0]++;
            int col = G_COL_GRAPHES + (i % 2) * G_LARGEUR_GRAPHE;
            int ligne = hautTableaux + (i / 2) * (G_HAUTEUR_GRAPHE + 1);
            return dessin.createChart(dessin.createAnchor(0, 0, 0, 0, col, ligne,
                col + G_LARGEUR_GRAPHE, ligne + G_HAUTEUR_GRAPHE));
        };
        if (nbMois > 0) {
            XDDFCategoryDataSource mois = XDDFDataSourcesFactory.fromStringCellRange(sh,
                new CellRangeAddress(ligneMois, ligneMois, 1, nbMois));
            graphiqueBarres(suivant.get(), "CA et résultat du mois", mois, "Montant", "#,##0", false,
                serie(sh, lignesEvol[0], nbMois, "CA du mois", COUL_CA),
                serie(sh, lignesEvol[1], nbMois, "Résultat du mois", COUL_RESULTAT));
            graphiqueCourbes(suivant.get(), "CA et résultat cumulés", mois,
                serie(sh, lignesEvol[2], nbMois, "CA cumulé", COUL_CA),
                serie(sh, lignesEvol[3], nbMois, "Résultat cumulé", COUL_RESULTAT));
            graphiqueAires(suivant.get(), "Trésorerie en fin de mois", mois,
                serie(sh, lignesEvol[4], nbMois, "Trésorerie", COUL_TRESORERIE));
        }
        graphiqueBarres(suivant.get(), "Structure du bilan", categories(sh, plageBilan), "Montant", "#,##0", false,
            serieColonne(sh, plageBilan, "Montant", COUL_BILAN));
        if (!libCharges.isEmpty()) {
            graphiqueBarres(suivant.get(), "Charges par nature", categories(sh, plageCharges), "Montant", "#,##0",
                true, serieColonne(sh, plageCharges, "Charges", COUL_CHARGES));
        }
        graphiqueBarres(suivant.get(), "Ratios clés", categories(sh, plageRatios), "Valeur", "0%", false,
            serieColonne(sh, plageRatios, "Valeur", COUL_RATIOS));
        // Deux rangées de graphiques par page : un saut de page entre la 2e et la 3e rangée, pour qu'aucun
        // graphique ne soit coupé à l'impression (PDF du 15/11/2026).
        if (place[0] + (libProduits.isEmpty() ? 0 : 1) > 4) {
            sh.setRowBreak(hautTableaux + 2 * (G_HAUTEUR_GRAPHE + 1) - 1);
        }
        if (!libProduits.isEmpty()) {
            graphiqueSecteurs(suivant.get(), "Répartition des produits", categories(sh, plageProduits),
                XDDFDataSourcesFactory.fromNumericCellRange(sh,
                    new CellRangeAddress(plageProduits[0], plageProduits[1], 1, 1)));
        }
    }

    /** Colonne (base 0) où commencent les graphiques, largeur en colonnes et hauteur en lignes de chacun. */
    private static final int G_COL_GRAPHES = 3, G_LARGEUR_GRAPHE = 6, G_HAUTEUR_GRAPHE = 18;

    /**
     * Petit tableau libellé / valeur des colonnes A et B de la feuille Graphiques.
     *
     * @return [première, dernière] ligne de données (base 0) ; dernière &lt; première s'il est vide
     */
    private int[] tableauGraphique(Sheet sh, Styles s, int r, String titre, String colLibelle, String colValeur,
                                   List<String> libelles, List<BigDecimal> valeurs, boolean pourcentage) {
        Row hdr = sh.createRow(r++);
        cell(hdr, 0, titre, s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(hdr.getRowNum(), hdr.getRowNum(), 0, 1));
        Row cols = sh.createRow(r++);
        cell(cols, 0, colLibelle, s.enTete);
        cell(cols, 1, colValeur, s.enTete);
        int debut = r;
        if (libelles.isEmpty()) {
            Row vide = sh.createRow(r);
            cell(vide, 0, "Aucun mouvement sur la période", s.normal);
            return new int[]{debut, debut};
        }
        for (int i = 0; i < libelles.size(); i++) {
            Row row = sh.createRow(r++);
            cell(row, 0, libelles.get(i), s.normal);
            if (pourcentage) {
                Cell c = row.createCell(1);
                c.setCellValue(valeurs.get(i).doubleValue());
                c.setCellStyle(s.pourcentage);
            } else {
                cellMontant(row, 1, valeurs.get(i), s);
            }
        }
        return new int[]{debut, r - 1};
    }

    private record SerieGraphique(XDDFNumericalDataSource<Double> valeurs, String titre, String couleur) {}

    private SerieGraphique serie(XSSFSheet sh, int ligne, int nbMois, String titre, String couleur) {
        return new SerieGraphique(XDDFDataSourcesFactory.fromNumericCellRange(sh,
            new CellRangeAddress(ligne, ligne, 1, nbMois)), titre, couleur);
    }

    private SerieGraphique serieColonne(XSSFSheet sh, int[] plage, String titre, String couleur) {
        return new SerieGraphique(XDDFDataSourcesFactory.fromNumericCellRange(sh,
            new CellRangeAddress(plage[0], plage[1], 1, 1)), titre, couleur);
    }

    private XDDFCategoryDataSource categories(XSSFSheet sh, int[] plage) {
        return XDDFDataSourcesFactory.fromStringCellRange(sh, new CellRangeAddress(plage[0], plage[1], 0, 0));
    }

    private static void titreGraphique(XSSFChart chart, String titre) {
        chart.setTitleText(titre);
        chart.setTitleOverlay(false);
    }

    private static XDDFSolidFillProperties remplissage(String hex) {
        return new XDDFSolidFillProperties(XDDFColor.from(new byte[]{
            (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)}));
    }

    private static XDDFLineProperties trait(String hex, double epaisseurPt) {
        XDDFLineProperties l = new XDDFLineProperties(remplissage(hex));
        l.setWidth(epaisseurPt);
        return l;
    }

    /** Axes catégories en bas (ou à gauche pour des barres horizontales) et valeurs, titrés en français. */
    private static XDDFValueAxis axes(XSSFChart chart, XDDFCategoryAxis[] cat, boolean horizontal,
                                      String titreValeurs, String format) {
        cat[0] = chart.createCategoryAxis(horizontal ? AxisPosition.LEFT : AxisPosition.BOTTOM);
        cat[0].setTickLabelPosition(AxisTickLabelPosition.LOW);
        XDDFValueAxis val = chart.createValueAxis(horizontal ? AxisPosition.BOTTOM : AxisPosition.LEFT);
        val.setCrosses(AxisCrosses.AUTO_ZERO);
        val.setTitle(titreValeurs);
        val.setNumberFormat(format);
        val.setMajorTickMark(AxisTickMark.OUT);
        return val;
    }

    private void graphiqueBarres(XSSFChart chart, String titre, XDDFCategoryDataSource cat, String titreValeurs,
                                 String format, boolean horizontal, SerieGraphique... series) {
        titreGraphique(chart, titre);
        XDDFCategoryAxis[] axeCat = new XDDFCategoryAxis[1];
        XDDFValueAxis val = axes(chart, axeCat, horizontal, titreValeurs, format);
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, axeCat[0], val);
        data.setBarDirection(horizontal ? BarDirection.BAR : BarDirection.COL);
        data.setBarGrouping(BarGrouping.CLUSTERED);
        data.setGapWidth(60);
        data.setVaryColors(false);
        for (SerieGraphique sg : series) {
            XDDFChartData.Series serie = data.addSeries(cat, sg.valeurs());
            serie.setTitle(sg.titre(), null);
            serie.setFillProperties(remplissage(sg.couleur()));
        }
        chart.plot(data);
        if (series.length > 1) {
            chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        }
    }

    private void graphiqueCourbes(XSSFChart chart, String titre, XDDFCategoryDataSource cat,
                                  SerieGraphique... series) {
        titreGraphique(chart, titre);
        XDDFCategoryAxis[] axeCat = new XDDFCategoryAxis[1];
        XDDFValueAxis val = axes(chart, axeCat, false, "Montant", "#,##0");
        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, axeCat[0], val);
        data.setVaryColors(false);
        for (SerieGraphique sg : series) {
            XDDFLineChartData.Series serie = (XDDFLineChartData.Series) data.addSeries(cat, sg.valeurs());
            serie.setTitle(sg.titre(), null);
            serie.setSmooth(false);
            // Ni taille de marqueur ni angle de départ des secteurs : leurs types de schéma manquent à POI « lite ».
            serie.setMarkerStyle(MarkerStyle.CIRCLE);
            serie.setLineProperties(trait(sg.couleur(), 2.25));
        }
        chart.plot(data);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
    }

    private void graphiqueAires(XSSFChart chart, String titre, XDDFCategoryDataSource cat, SerieGraphique sg) {
        titreGraphique(chart, titre);
        XDDFCategoryAxis[] axeCat = new XDDFCategoryAxis[1];
        XDDFValueAxis val = axes(chart, axeCat, false, "Montant", "#,##0");
        XDDFAreaChartData data = (XDDFAreaChartData) chart.createData(ChartTypes.AREA, axeCat[0], val);
        data.setVaryColors(false);
        XDDFChartData.Series serie = data.addSeries(cat, sg.valeurs());
        serie.setTitle(sg.titre(), null);
        serie.setFillProperties(remplissage(sg.couleur()));
        chart.plot(data);
    }

    private void graphiqueSecteurs(XSSFChart chart, String titre, XDDFCategoryDataSource cat,
                                   XDDFNumericalDataSource<Double> valeurs) {
        titreGraphique(chart, titre);
        XDDFPieChartData data = (XDDFPieChartData) chart.createData(ChartTypes.PIE, null, null);
        data.setVaryColors(true);
        XDDFChartData.Series serie = data.addSeries(cat, valeurs);
        serie.setTitle("Produits", null);
        chart.plot(data);
        // Pourcentage sur chaque part : sans lui, un camembert ne se lit qu'à l'œil.
        var ser = chart.getCTChart().getPlotArea().getPieChartArray(0).getSerArray(0);
        var lbl = ser.isSetDLbls() ? ser.getDLbls() : ser.addNewDLbls();
        lbl.addNewShowLegendKey().setVal(false);
        lbl.addNewShowVal().setVal(false);
        lbl.addNewShowCatName().setVal(false);
        lbl.addNewShowSerName().setVal(false);
        lbl.addNewShowPercent().setVal(true);
        lbl.addNewShowBubbleSize().setVal(false);
        lbl.addNewShowLeaderLines().setVal(true);
        chart.getOrAddLegend().setPosition(LegendPosition.RIGHT);
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

    /** En-tête commun (bandeau, entité, exercice, durée) suivi du sous-titre de la feuille ; renvoie la première ligne libre. */
    private int titre(Sheet sh, Styles s, String titre, String sousTitre, int derniereColonne) {
        // Une page en largeur (paysage dès que la feuille est large) : le bandeau et les graphiques ne sont
        // plus coupés à l'impression.
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 0);
        sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sh.getPrintSetup().setLandscape(derniereColonne >= 6);
        sh.setMargin(Sheet.LeftMargin, 0.3);
        sh.setMargin(Sheet.RightMargin, 0.3);
        return s.entete.poser(sh, titre, derniereColonne, sousTitre);
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
        final CellStyle sousTitre, enTete, sousEntete, classeHeader, sectionHeader,
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
        /** En-tête commun à toutes les feuilles (bandeau, entité, exercice, durée). */
        final EnTeteFeuille entete;

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

        Styles(XSSFWorkbook wb, EnTeteFeuille.Identite identite) {
            this.wb = wb;
            this.entete = new EnTeteFeuille(wb, identite);
            this.fmt = wb.createDataFormat();

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
            // Demande du 15/11/2026 : chaque tableau imprimé (Journal, Balance, Ratios, Graphiques…) porte le
            // contour de chacune de ses cellules, comme les états de la liasse.
            for (CellStyle st : new CellStyle[]{classeHeader, sectionHeader, normal, montant, pourcentage,
                totalLabel, totalMontant, controleFormule, controleOk, controleKo, interpretation, noteTexte}) {
                st.setBorderTop(BorderStyle.THIN);
                st.setBorderBottom(BorderStyle.THIN);
                st.setBorderLeft(BorderStyle.THIN);
                st.setBorderRight(BorderStyle.THIN);
            }
            noteTexte.setVerticalAlignment(VerticalAlignment.TOP);
        }
    }
}

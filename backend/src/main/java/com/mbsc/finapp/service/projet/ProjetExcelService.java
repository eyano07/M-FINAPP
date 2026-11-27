package com.mbsc.finapp.service.projet;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Classeurs Excel des projets (V167) : par projet (synthèse, fiche de stock, mouvements, acquisitions, dépenses), ou tous
 * les projets (synthèse, puis la fiche de stock de chacun). Montants en USD.
 */
@Service
@RequiredArgsConstructor
public class ProjetExcelService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ProjetService projets;

    @PreAuthorize(ProjetService.LECTURE)
    @Transactional(readOnly = true)
    public byte[] projet(Long id) {
        ProjetService.Detail d = projets.consulter(id);
        return classeur(wb -> {
            Styles s = new Styles(wb);
            synthese(wb.createSheet("Synthèse"), s, List.of(d));
            ficheStock(wb.createSheet("Fiche de stock"), s, d);
            mouvements(wb.createSheet("Mouvements"), s, d);
            acquisitions(wb.createSheet("Acquisitions"), s, d);
            depenses(wb.createSheet("Dépenses"), s, d);
        });
    }

    @PreAuthorize(ProjetService.LECTURE)
    @Transactional(readOnly = true)
    public byte[] tous() {
        List<ProjetService.Detail> details = projets.lister().stream().map(p -> projets.consulter(p.id())).toList();
        return classeur(wb -> {
            Styles s = new Styles(wb);
            synthese(wb.createSheet("Synthèse"), s, details);
            for (ProjetService.Detail d : details) {
                String nom = (d.projet().code() + " " + d.projet().nom()).replaceAll("[\\\\/?*\\[\\]:]", " ");
                ficheStock(wb.createSheet(nom.length() > 31 ? nom.substring(0, 31) : nom), s, d);
            }
        });
    }

    private interface Remplissage { void remplir(XSSFWorkbook wb); }

    private static byte[] classeur(Remplissage r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            r.remplir(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Classeur des projets impossible à générer", e);
        }
    }

    private static void synthese(Sheet sh, Styles s, List<ProjetService.Detail> details) {
        int r = titre(sh, s, "Projets — synthèse des dépenses (USD)");
        r = entetes(sh, s, r, "Code", "Projet", "Statut", "Budget", "Prévu", "Engagé", "Payé", "Reste");
        for (ProjetService.Detail d : details) {
            ProjetService.Projet p = d.projet();
            ProjetService.Depenses dp = d.depenses();
            Row row = sh.createRow(r++);
            texte(row, 0, p.code(), s.normal);
            texte(row, 1, p.nom(), s.normal);
            texte(row, 2, p.statut(), s.normal);
            texte(row, 3, p.budgetReference(), s.normal);
            montant(row, 4, dp.prevuBudget() != null ? dp.prevuBudget() : dp.prevuBesoins(), s.montant);
            montant(row, 5, dp.engage(), s.montant);
            montant(row, 6, dp.paye(), s.montant);
            montant(row, 7, dp.reste(), s.montant);
        }
        largeurs(sh, 8);
    }

    private static void ficheStock(Sheet sh, Styles s, ProjetService.Detail d) {
        int r = titre(sh, s, "Fiche de stock — " + d.projet().code() + " " + d.projet().nom());
        r = entetes(sh, s, r, "Désignation", "Nature", "Compte", "Unité", "Prévu", "Entrées", "Sorties", "Solde", "Valeur (USD)", "Payé (USD)");
        for (ProjetService.Article a : d.articles()) {
            Row row = sh.createRow(r++);
            texte(row, 0, a.designation(), s.normal);
            texte(row, 1, "ACHAT".equals(a.nature()) ? "Achat divers" : "Acquisition", s.normal);
            texte(row, 2, a.compteNumero(), s.normal);
            texte(row, 3, a.unite(), s.normal);
            montant(row, 4, a.quantitePrevue(), s.quantite);
            montant(row, 5, a.entrees(), s.quantite);
            montant(row, 6, a.sorties(), s.quantite);
            montant(row, 7, a.solde(), s.quantite);
            montant(row, 8, a.valeurUsd(), s.montant);
            montant(row, 9, a.payeUsd(), s.montant);
        }
        largeurs(sh, 10);
    }

    private static void mouvements(Sheet sh, Styles s, ProjetService.Detail d) {
        int r = titre(sh, s, "Mouvements — " + d.projet().code() + " " + d.projet().nom());
        r = entetes(sh, s, r, "Date", "Désignation", "Mouvement", "Quantité", "Montant (USD)", "Note", "Projet lié", "Commentaire", "Par");
        for (ProjetService.Mouvement m : d.mouvements()) {
            Row row = sh.createRow(r++);
            texte(row, 0, m.date() == null ? "" : m.date().format(DATE), s.normal);
            texte(row, 1, m.designation(), s.normal);
            texte(row, 2, m.type(), s.normal);
            montant(row, 3, m.quantite(), s.quantite);
            montant(row, 4, m.montantUsd(), s.montant);
            texte(row, 5, m.noteReference(), s.normal);
            texte(row, 6, m.projetLie(), s.normal);
            texte(row, 7, m.commentaire(), s.normal);
            texte(row, 8, m.auteur(), s.normal);
        }
        largeurs(sh, 9);
    }

    private static void acquisitions(Sheet sh, Styles s, ProjetService.Detail d) {
        int r = titre(sh, s, "Acquisitions affectées — " + d.projet().code() + " " + d.projet().nom());
        r = entetes(sh, s, r, "Référence", "Libellé", "Compte", "Acquise le", "Valeur (USD)", "Statut");
        for (ProjetService.Acquisition a : d.acquisitions()) {
            Row row = sh.createRow(r++);
            texte(row, 0, a.reference(), s.normal);
            texte(row, 1, a.libelle(), s.normal);
            texte(row, 2, a.compte(), s.normal);
            texte(row, 3, a.dateAcquisition() == null ? "" : a.dateAcquisition().format(DATE), s.normal);
            montant(row, 4, a.valeurUsd(), s.montant);
            texte(row, 5, a.statut(), s.normal);
        }
        largeurs(sh, 6);
    }

    private static void depenses(Sheet sh, Styles s, ProjetService.Detail d) {
        ProjetService.Depenses dp = d.depenses();
        int r = titre(sh, s, "Dépenses — " + d.projet().code() + " " + d.projet().nom() + " (USD)");
        String[][] lignes = {{"Prévu au budget", null}, {"Prévu (besoins)", null}, {"Engagé", null}, {"Payé", null}, {"Reste", null}};
        BigDecimal[] valeurs = {dp.prevuBudget(), dp.prevuBesoins(), dp.engage(), dp.paye(), dp.reste()};
        for (int i = 0; i < lignes.length; i++) {
            Row row = sh.createRow(r++);
            texte(row, 0, lignes[i][0], s.gras);
            montant(row, 1, valeurs[i], s.montant);
        }
        r++;
        r = entetes(sh, s, r, "Note", "Objet", "Statut", "Montant (USD)");
        for (ProjetService.NoteLiee n : dp.notes()) {
            Row row = sh.createRow(r++);
            texte(row, 0, n.reference(), s.normal);
            texte(row, 1, n.objet(), s.normal);
            texte(row, 2, n.statut(), s.normal);
            montant(row, 3, n.montantUsd(), s.montant);
        }
        largeurs(sh, 4);
    }

    private static int titre(Sheet sh, Styles s, String titre) {
        Cell c = sh.createRow(0).createCell(0);
        c.setCellValue(titre);
        c.setCellStyle(s.titre);
        return 2;
    }

    private static int entetes(Sheet sh, Styles s, int r, String... titres) {
        Row row = sh.createRow(r);
        for (int i = 0; i < titres.length; i++) texte(row, i, titres[i], s.entete);
        sh.createFreezePane(0, r + 1);
        return r + 1;
    }

    private static void texte(Row row, int col, String v, CellStyle st) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(st);
    }

    private static void montant(Row row, int col, BigDecimal v, CellStyle st) {
        Cell c = row.createCell(col);
        if (v != null) c.setCellValue(v.doubleValue());
        c.setCellStyle(st);
    }

    private static void largeurs(Sheet sh, int n) {
        for (int i = 0; i < n; i++) {
            sh.autoSizeColumn(i);
            sh.setColumnWidth(i, Math.min(Math.max(sh.getColumnWidth(i) + 512, 3000), 16000));
        }
    }

    private static final class Styles {
        final CellStyle titre, entete, normal, gras, montant, quantite;

        Styles(XSSFWorkbook wb) {
            Font fTitre = wb.createFont();
            fTitre.setBold(true);
            fTitre.setFontHeightInPoints((short) 13);
            titre = wb.createCellStyle();
            titre.setFont(fTitre);
            Font fGras = wb.createFont();
            fGras.setBold(true);
            Font fBlanc = wb.createFont();
            fBlanc.setBold(true);
            fBlanc.setColor(IndexedColors.WHITE.getIndex());
            entete = wb.createCellStyle();
            entete.setFont(fBlanc);
            entete.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            normal = wb.createCellStyle();
            normal.setBorderBottom(BorderStyle.HAIR);
            gras = wb.createCellStyle();
            gras.setFont(fGras);
            montant = wb.createCellStyle();
            montant.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            montant.setBorderBottom(BorderStyle.HAIR);
            quantite = wb.createCellStyle();
            quantite.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
            quantite.setBorderBottom(BorderStyle.HAIR);
        }
    }
}

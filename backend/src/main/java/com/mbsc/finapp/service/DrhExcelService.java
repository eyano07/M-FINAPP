package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports Excel du module DRH : registre des employés et registre de paie
 * ("DEBOURS MBSC", sur le modèle du classeur de référence — une ligne par
 * bulletin de la période, mêmes colonnes que {@code ResultatCalculPaie}).
 */
@Service
@RequiredArgsConstructor
public class DrhExcelService {

    private static final String FORMAT_MONTANT = "#,##0.00;[RED]-#,##0.00";
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmployeRepository employeRepository;
    private final BulletinPaieRepository bulletinRepository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] genererClasseurEmployes() {
        List<Employe> employes = employeRepository.findByActifTrueOrderByNomComplet();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            XSSFSheet sheet = wb.createSheet("Employés");

            String[] entetes = {
                "Matricule", "Nom complet", "Catégorie", "Affectation", "Email", "Téléphone",
                "Date d'embauche", "Salaire de base (USD)", "Situation familiale", "Enfants",
                "Diplôme", "Ancienneté (années)", "Rendement (%)", "Conforme", "Superviseur", "Expatrié",
            };
            Row header = sheet.createRow(0);
            for (int c = 0; c < entetes.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(entetes[c]);
                cell.setCellStyle(s.enTete);
            }

            int r = 1;
            for (Employe e : employes) {
                Row row = sheet.createRow(r++);
                int c = 0;
                texte(row, c++, e.getMatricule(), s.normal);
                texte(row, c++, e.getNomComplet(), s.normal);
                texte(row, c++, e.getCategorie(), s.normal);
                texte(row, c++, e.getAffectation(), s.normal);
                texte(row, c++, e.getEmail(), s.normal);
                texte(row, c++, e.getTelephone(), s.normal);
                texte(row, c++, e.getDateEmbauche() != null ? e.getDateEmbauche().format(DATE_FR) : "", s.normal);
                montant(row, c++, e.getSalaireBaseUsd(), s.montant);
                texte(row, c++, e.getSituationFamiliale() != null ? e.getSituationFamiliale().name() : "", s.normal);
                entier(row, c++, e.getNombreEnfants(), s.normal);
                texte(row, c++, e.getDiplome(), s.normal);
                entier(row, c++, e.getAncienneteAnnees(), s.normal);
                montant(row, c++, e.getRendementPct(), s.montant);
                texte(row, c++, e.isConforme() ? "Oui" : "Non", s.normal);
                texte(row, c++, e.isSuperviseur() ? "Oui" : "Non", s.normal);
                texte(row, c, e.isExpatrie() ? "Oui" : "Non", s.normal);
            }
            for (int c = 0; c < entetes.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le registre des employés", e);
        }
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] genererDeboursMbsc(Integer mois, Integer annee) {
        List<BulletinPaie> bulletins = bulletinRepository.findByPeriode(mois, annee);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            XSSFSheet sheet = wb.createSheet("DEBOURS MBSC " + mois + "-" + annee);

            String[] entetes = {
                "Matricule", "Nom complet", "Enfants", "Présence (%)", "Salaire de base (USD)",
                "Salaire brut (R)", "Indemnité logement (I)", "Indemnité transport (J)",
                "Congé", "Heures suppl.", "Alloc. familiale", "Prime diplôme", "Prime ancienneté",
                "Prime rendement", "Base INPP (Q)", "CNSS ouvrière (T)", "CNSS patronale (U)",
                "ONEM (V)", "Total INSS (W)", "INPP (X)", "IPR (Z)", "Avance salaire", "Prêt",
                "Salaire net (AC)", "Taux de change", "Net (FC)", "Statut",
            };
            Row header = sheet.createRow(0);
            for (int c = 0; c < entetes.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(entetes[c]);
                cell.setCellStyle(s.enTete);
            }

            int r = 1;
            for (BulletinPaie b : bulletins) {
                Row row = sheet.createRow(r++);
                int c = 0;
                texte(row, c++, b.getEmploye().getMatricule(), s.normal);
                texte(row, c++, b.getEmploye().getNomComplet(), s.normal);
                entier(row, c++, b.getNombreEnfants(), s.normal);
                montant(row, c++, b.getPresencePct(), s.montant);
                montant(row, c++, b.getSalaireBaseUsd(), s.montant);
                montant(row, c++, b.getSalaireBrut(), s.montant);
                montant(row, c++, b.getIndemniteLogement(), s.montant);
                montant(row, c++, b.getIndemniteTransport(), s.montant);
                montant(row, c++, b.getConge(), s.montant);
                montant(row, c++, b.getHeuresSupplementaires(), s.montant);
                montant(row, c++, b.getAllocationFamiliale(), s.montant);
                montant(row, c++, b.getPrimeDiplome(), s.montant);
                montant(row, c++, b.getPrimeAnciennete(), s.montant);
                montant(row, c++, b.getPrimeRendement(), s.montant);
                montant(row, c++, b.getBaseImposableInpp(), s.montant);
                montant(row, c++, b.getCnssOuvriere(), s.montant);
                montant(row, c++, b.getCnssPatronale(), s.montant);
                montant(row, c++, b.getOnem(), s.montant);
                montant(row, c++, b.getTotalInss(), s.montant);
                montant(row, c++, b.getInpp(), s.montant);
                montant(row, c++, b.getIpr(), s.montant);
                montant(row, c++, b.getAvanceSalaire(), s.montant);
                montant(row, c++, b.getPret(), s.montant);
                montant(row, c++, b.getSalaireNet(), s.totalMontant);
                montant(row, c++, b.getTauxChangeApplique(), s.montant);
                montant(row, c++, b.getNetFc(), s.montant);
                texte(row, c, b.getStatut() != null ? b.getStatut().name() : "", s.normal);
            }

            if (r > 1) {
                Row total = sheet.createRow(r);
                Cell label = total.createCell(0);
                label.setCellValue("TOTAL");
                label.setCellStyle(s.totalLabel);
                montant(total, 23, sommeColonne(bulletins, BulletinPaie::getSalaireNet), s.totalMontant);
                montant(total, 25, sommeColonne(bulletins, BulletinPaie::getNetFc), s.totalMontant);
            }

            for (int c = 0; c < entetes.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de générer le registre de paie", e);
        }
    }

    private java.math.BigDecimal sommeColonne(List<BulletinPaie> bulletins,
                                               java.util.function.Function<BulletinPaie, java.math.BigDecimal> f) {
        return bulletins.stream().map(f).filter(java.util.Objects::nonNull)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private void texte(Row row, int col, String valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(valeur != null ? valeur : "");
        cell.setCellStyle(style);
    }

    private void entier(Row row, int col, Integer valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        if (valeur != null) cell.setCellValue(valeur);
        cell.setCellStyle(style);
    }

    private void montant(Row row, int col, java.math.BigDecimal valeur, CellStyle style) {
        Cell cell = row.createCell(col);
        if (valeur != null) cell.setCellValue(valeur.doubleValue());
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle enTete, normal, montant, totalLabel, totalMontant;

        Styles(XSSFWorkbook wb) {
            Font fBlanc = wb.createFont();
            fBlanc.setBold(true);
            fBlanc.setColor(IndexedColors.WHITE.getIndex());
            enTete = wb.createCellStyle();
            enTete.setFont(fBlanc);
            enTete.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            enTete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            enTete.setAlignment(HorizontalAlignment.CENTER);
            enTete.setWrapText(true);

            normal = wb.createCellStyle();

            DataFormat fmt = wb.createDataFormat();
            montant = wb.createCellStyle();
            montant.setDataFormat(fmt.getFormat(FORMAT_MONTANT));
            montant.setAlignment(HorizontalAlignment.RIGHT);

            Font fTotal = wb.createFont();
            fTotal.setBold(true);
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(fTotal);
            totalLabel.setBorderTop(BorderStyle.THIN);

            totalMontant = wb.createCellStyle();
            totalMontant.cloneStyleFrom(montant);
            totalMontant.setFont(fTotal);
            totalMontant.setBorderTop(BorderStyle.THIN);
        }
    }
}

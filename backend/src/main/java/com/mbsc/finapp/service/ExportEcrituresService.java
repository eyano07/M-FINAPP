package com.mbsc.finapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Éditions obligatoires du grand livre (audit SYSCOHADA du 10/11/2026, C-19 et C-25) : export de toutes les
 * écritures d'une période au format d'échange des fichiers d'écritures (une ligne par écriture, pour
 * l'administration fiscale ou le commissaire aux comptes), grand livre général de tous les comptes, et
 * centralisation mensuelle des journaux.
 */
@Service
@RequiredArgsConstructor
public class ExportEcrituresService {

    private static final String SEPARATEUR = ";";

    private final JdbcTemplate jdbc;

    /** Une écriture du grand livre général. */
    public record LigneGrandLivre(LocalDate date, String journal, String piece, String libelle,
                                 BigDecimal debit, BigDecimal credit, BigDecimal solde) {
    }

    /** Un compte du grand livre général : report, écritures de la période et totaux. */
    public record CompteGrandLivre(String numero, String libelle, BigDecimal reportDebit, BigDecimal reportCredit,
                                   List<LigneGrandLivre> lignes, BigDecimal totalDebit, BigDecimal totalCredit,
                                   BigDecimal soldeFinal) {
    }

    /** Une ligne de la centralisation : un journal, un mois. */
    public record LigneCentralisation(String journal, int annee, int mois, long pieces, BigDecimal debit,
                                      BigDecimal credit) {
    }

    /**
     * Fichier des écritures : CSV séparé par des points-virgules, encodé en UTF-8 avec marque d'ordre des octets
     * (lisible directement par un tableur), une ligne par écriture comptabilisée ou annulée de la période.
     */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] fichierEcritures(LocalDate du, LocalDate au) {
        return fichierEcrituresInterne(du, au);
    }

    /** Même fichier, sans contrôle de rôle : pour l'archive de clôture. */
    public byte[] fichierEcrituresInterne(LocalDate du, LocalDate au) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(SEPARATEUR, "JournalCode", "EcritureNum", "EcritureDate", "CompteNum", "CompteLib",
            "PieceRef", "PieceDate", "EcritureLib", "Debit", "Credit", "ValidDate", "ValidePar", "Montantdevise",
            "Idevise", "Statut", "SoldeOuverture", "PieceOrigine")).append("\r\n");
        jdbc.query("""
            select p.journal, g.id, g.date_ecriture, c.numero, c.libelle, p.reference, p.date_piece, g.libelle,
                   g.debit, g.credit, p.comptabilisee_le, u.email, g.montant_devise, g.devise, p.statut,
                   p.solde_ouverture, o.reference
            from grand_livre g
            join comptes_ohada c on c.id = g.compte_id
            left join pieces_comptables p on p.id = g.piece_id
            left join pieces_comptables o on o.id = p.piece_origine_id
            left join users u on u.id = p.comptabilisee_par_id
            where g.date_ecriture between ? and ? and (p.id is null or p.statut <> 'BROUILLON')
            order by g.date_ecriture, p.reference, g.id
            """, rs -> {
            sb.append(String.join(SEPARATEUR,
                texte(rs.getString(1)), String.valueOf(rs.getLong(2)), texte(rs.getString(3)), texte(rs.getString(4)),
                texte(rs.getString(5)), texte(rs.getString(6)), texte(rs.getString(7)), texte(rs.getString(8)),
                montant(rs.getBigDecimal(9)), montant(rs.getBigDecimal(10)), texte(rs.getString(11)),
                texte(rs.getString(12)), rs.getBigDecimal(13) == null ? "" : montant(rs.getBigDecimal(13)),
                rs.getBigDecimal(13) == null ? "" : texte(rs.getString(14)), texte(rs.getString(15)),
                rs.getBoolean(16) ? "O" : "N", texte(rs.getString(17)))).append("\r\n");
        }, du, au);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Grand livre général : tous les comptes mouvementés, avec leur report à nouveau. */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CompteGrandLivre> grandLivreGeneral(LocalDate du, LocalDate au) {
        Map<String, BigDecimal[]> reports = new LinkedHashMap<>();
        jdbc.query("""
            select c.numero, coalesce(sum(g.debit), 0), coalesce(sum(g.credit), 0)
            from grand_livre g join comptes_ohada c on c.id = g.compte_id
            left join pieces_comptables p on p.id = g.piece_id
            where g.date_ecriture < ? and (p.id is null or p.statut <> 'BROUILLON')
            group by c.numero
            """, rs -> {
            reports.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
        }, du);
        Map<String, CompteEnCours> comptes = new LinkedHashMap<>();
        jdbc.query("""
            select c.numero, c.libelle, g.date_ecriture, p.journal, p.reference, g.libelle, g.debit, g.credit
            from grand_livre g join comptes_ohada c on c.id = g.compte_id
            left join pieces_comptables p on p.id = g.piece_id
            where g.date_ecriture between ? and ? and (p.id is null or p.statut <> 'BROUILLON')
            order by c.numero, g.date_ecriture, g.id
            """, rs -> {
            CompteEnCours cc = comptes.computeIfAbsent(rs.getString(1), n -> nouveau(n, libelle(rs), reports));
            cc.ajouter(rs);
        }, du, au);
        // Comptes sans mouvement dans la période mais avec un solde reporté.
        List<String> sansMouvement = new ArrayList<>(reports.keySet());
        sansMouvement.removeAll(comptes.keySet());
        if (!sansMouvement.isEmpty()) {
            Map<String, String> libelles = new LinkedHashMap<>();
            jdbc.query("select numero, libelle from comptes_ohada", rs -> {
                libelles.put(rs.getString(1), rs.getString(2));
            });
            for (String n : sansMouvement) {
                BigDecimal[] r = reports.get(n);
                if (r[0].compareTo(r[1]) != 0) comptes.put(n, new CompteEnCours(n, libelles.get(n), r[0], r[1]));
            }
        }
        return comptes.values().stream()
            .sorted((a, b) -> a.numero.compareTo(b.numero))
            .map(CompteEnCours::fige)
            .toList();
    }

    /** Centralisation des journaux : nombre de pièces, débit et crédit par journal et par mois. */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<LigneCentralisation> centralisation(LocalDate du, LocalDate au) {
        return jdbc.query("""
            select p.journal, extract(year from p.date_piece)::int, extract(month from p.date_piece)::int,
                   count(distinct p.id), coalesce(sum(g.debit), 0), coalesce(sum(g.credit), 0)
            from pieces_comptables p join grand_livre g on g.piece_id = p.id
            where p.date_piece between ? and ? and p.statut <> 'BROUILLON'
            group by 1, 2, 3
            order by 2, 3, 1
            """, (rs, i) -> new LigneCentralisation(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4),
            rs.getBigDecimal(5), rs.getBigDecimal(6)), du, au);
    }

    private static String libelle(ResultSet rs) {
        try {
            return rs.getString(2);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompteEnCours nouveau(String numero, String libelle, Map<String, BigDecimal[]> reports) {
        BigDecimal[] r = reports.getOrDefault(numero, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        return new CompteEnCours(numero, libelle, r[0], r[1]);
    }

    private static final class CompteEnCours {
        private final String numero;
        private final String libelle;
        private final BigDecimal reportDebit;
        private final BigDecimal reportCredit;
        private final List<LigneGrandLivre> lignes = new ArrayList<>();
        private BigDecimal debit = BigDecimal.ZERO;
        private BigDecimal credit = BigDecimal.ZERO;
        private BigDecimal solde;

        CompteEnCours(String numero, String libelle, BigDecimal reportDebit, BigDecimal reportCredit) {
            this.numero = numero;
            this.libelle = libelle;
            this.reportDebit = reportDebit;
            this.reportCredit = reportCredit;
            this.solde = reportDebit.subtract(reportCredit);
        }

        void ajouter(ResultSet rs) throws SQLException {
            BigDecimal d = rs.getBigDecimal(7);
            BigDecimal c = rs.getBigDecimal(8);
            debit = debit.add(d);
            credit = credit.add(c);
            solde = solde.add(d).subtract(c);
            lignes.add(new LigneGrandLivre(rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
                rs.getString(6), d, c, solde));
        }

        CompteGrandLivre fige() {
            return new CompteGrandLivre(numero, libelle, reportDebit, reportCredit, lignes, debit, credit, solde);
        }
    }

    private static String texte(String v) {
        if (v == null) return "";
        String s = v.replace("\r", " ").replace("\n", " ");
        return s.contains(SEPARATEUR) || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String montant(BigDecimal v) {
        return v == null ? "0,00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}

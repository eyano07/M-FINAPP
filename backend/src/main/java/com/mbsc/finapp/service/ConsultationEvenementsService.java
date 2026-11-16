package com.mbsc.finapp.service;

import com.mbsc.finapp.dto.comptabilite.JournalEvenementsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Consultation du journal des événements comptables et contrôle de sa chaîne d'empreintes (C-09). */
@Service
@RequiredArgsConstructor
public class ConsultationEvenementsService {

    private final JdbcTemplate jdbc;
    private final JournalEvenementsComptables journal;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public JournalEvenementsResponse consulter(int limite) {
        int n = Math.max(1, Math.min(limite, 2000));
        Long alteree = journal.premiereLigneAlteree();
        Long total = jdbc.queryForObject("select count(*) from journal_evenements_comptables", Long.class);
        List<JournalEvenementsResponse.Evenement> lignes = jdbc.query("""
            select id, horodatage, auteur, action, objet_type, objet_id, objet_reference, avant::text, apres::text,
                   motif, empreinte
            from journal_evenements_comptables order by id desc limit ?
            """, (rs, i) -> new JournalEvenementsResponse.Evenement(rs.getLong(1),
            rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4), rs.getString(5),
            (Long) rs.getObject(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
            rs.getString(11)), n);
        return new JournalEvenementsResponse(alteree == null, alteree, total == null ? 0 : total, lignes);
    }
}

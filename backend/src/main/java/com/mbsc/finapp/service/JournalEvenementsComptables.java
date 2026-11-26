package com.mbsc.finapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal des événements comptables (audit SYSCOHADA du 10/11/2026, C-09) : chaque action sur une pièce, une
 * clôture ou un paramètre comptable y laisse une ligne avec l'état avant et après, son auteur et son motif.
 *
 * <p>La table est en ajout seul et chaînée par empreinte SHA-256 (V136) : la base calcule l'empreinte à l'insertion
 * et refuse toute modification ou suppression ; {@code verifier_journal_evenements()} retrouve la première ligne dont
 * l'empreinte ne correspond plus. L'écriture se fait dans la transaction de l'action : une action annulée ne laisse
 * pas de trace, une action validée en laisse toujours une. Le fichier {@code audit.log} reste en complément.</p>
 */
@Component
@RequiredArgsConstructor
public class JournalEvenementsComptables {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Enregistre un événement ; {@code avant} et {@code apres} sont convertis en JSON (null : rien). */
    public void enregistrer(String action, String objetType, Long objetId, String reference,
                            Object avant, Object apres, String motif, User auteur) {
        jdbc.update("""
            insert into journal_evenements_comptables
                (auteur_id, auteur, action, objet_type, objet_id, objet_reference, avant, apres, motif, empreinte)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, '')
            """,
            auteur == null ? null : auteur.getId(),
            auteur == null ? "système" : auteur.getEmail(),
            action, objetType, objetId, reference, json(avant), json(apres), motif);
    }

    /** Événement portant sur une pièce comptable. */
    public void piece(String action, PieceComptable piece, Map<String, Object> avant, String motif, User auteur) {
        enregistrer(action, "PIECE", piece.getId(), piece.getReference(), avant,
            piece.getStatut() == null ? null : instantane(piece), motif, auteur);
    }

    /** État d'une pièce : en-tête et lignes, tel qu'il est enregistré au journal. */
    public static Map<String, Object> instantane(PieceComptable p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", p.getReference());
        m.put("date", p.getDatePiece() == null ? null : p.getDatePiece().toString());
        m.put("journal", p.getJournal() == null ? null : p.getJournal().name());
        m.put("libelle", p.getLibelle());
        m.put("statut", p.getStatut() == null ? null : p.getStatut().name());
        m.put("soldeOuverture", p.isSoldeOuverture());
        m.put("totalDebit", p.getTotalDebit());
        m.put("totalCredit", p.getTotalCredit());
        List<Map<String, Object>> lignes = p.getLignes() == null ? List.of() : p.getLignes().stream()
            .map(JournalEvenementsComptables::ligne).toList();
        m.put("lignes", lignes);
        return m;
    }

    private static Map<String, Object> ligne(EcritureGrandLivre e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compte", e.getCompte() == null ? null : e.getCompte().getNumero());
        m.put("debit", e.getDebit());
        m.put("credit", e.getCredit());
        m.put("libelle", e.getLibelle());
        return m;
    }

    /** Identifiant de la première ligne dont l'empreinte ne correspond plus, ou null si la chaîne est intacte. */
    public Long premiereLigneAlteree() {
        return jdbc.queryForObject("select verifier_journal_evenements()", Long.class);
    }

    private String json(Object valeur) {
        if (valeur == null) return null;
        try {
            return mapper.writeValueAsString(valeur);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Événement comptable illisible", e);
        }
    }
}

package com.mbsc.finapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.dto.budget.LigneBudgetResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Copies d'un budget à chaque soumission au DA (V165) : les lignes telles que soumises, pour montrer au DA ce qui a
 * changé depuis la soumission précédente (budget rejeté, puis modifié et soumis à nouveau).
 */
@Service
@RequiredArgsConstructor
public class BudgetSoumissions {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** Une soumission : son numéro (1, 2...), sa date, son auteur et les lignes soumises. */
    public record Soumission(int numero, OffsetDateTime soumisLe, String soumisPar, List<LigneBudgetResponse> lignes) { }

    /** Enregistre la copie des lignes soumises (appelé dans la transaction de la soumission). */
    public void enregistrer(Long budgetId, String auteur, List<LigneBudgetResponse> lignes) {
        try {
            jdbc.update("""
                insert into budget_soumissions (budget_id, numero, soumis_par, lignes)
                values (?, coalesce((select max(numero) from budget_soumissions where budget_id = ?), 0) + 1, ?, ?)
                """, budgetId, budgetId, auteur, json.writeValueAsString(lignes));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Copie des lignes soumises impossible", e);
        }
    }

    /** Soumissions d'un budget, de la première à la dernière. */
    public List<Soumission> lister(Long budgetId) {
        return jdbc.query("select numero, soumis_le, soumis_par, lignes from budget_soumissions where budget_id = ? order by numero",
            (rs, i) -> {
                try {
                    return new Soumission(rs.getInt("numero"), rs.getObject("soumis_le", OffsetDateTime.class), rs.getString("soumis_par"),
                        json.readValue(rs.getString("lignes"), new TypeReference<List<LigneBudgetResponse>>() { }));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("Copie des lignes soumises illisible", e);
                }
            }, budgetId);
    }
}

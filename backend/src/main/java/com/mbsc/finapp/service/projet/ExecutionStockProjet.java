package com.mbsc.finapp.service.projet;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exécution du budget d'un projet d'après le stock des projets (V169), mois par mois, en USD, par compte « Projet — … » :
 * <ul>
 *   <li><b>Reçu</b> (réalisé) : valeur des biens affectés au projet par le logisticien (affectations, transferts reçus
 *       moins transferts envoyés ; achats entrés directement dans le projet avant le stock commun, retours de fonds).</li>
 *   <li><b>En attente</b> (engagé) : valeur des biens payés qui attendent dans le stock commun des projets, pas encore
 *       affectés.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExecutionStockProjet {

    private final JdbcTemplate jdbc;

    public record Execution(Map<String, BigDecimal[]> recu, Map<String, BigDecimal[]> enAttente) { }

    /** Exécution du projet dont c'est le budget (lignée {@code racineBudgetId}) ; null si ce n'est pas un budget de projet. */
    public Execution pour(Long racineBudgetId, int exercice) {
        List<Long> projets = jdbc.queryForList("select id from projets where budget_id = ?", Long.class, racineBudgetId);
        if (projets.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal[]> recu = new HashMap<>();
        jdbc.query("""
            select c.numero, extract(month from m.date_mouvement)::int as mois, sum(m.montant_usd) as montant
            from projet_mouvements m
            join projet_articles a on a.id = m.article_id
            join comptes_ohada c on c.id = a.compte_id
            where a.projet_id = ? and extract(year from m.date_mouvement) = ?
              and m.type in ('ACHAT', 'RETOUR_FONDS', 'AFFECTATION', 'TRANSFERT_ENTREE', 'TRANSFERT_SORTIE')
            group by c.numero, mois
            """, rs -> {
            ajouter(recu, rs.getString("numero"), rs.getInt("mois"), rs.getBigDecimal("montant"));
        }, projets.get(0), exercice);
        Map<String, BigDecimal[]> enAttente = new HashMap<>();
        jdbc.query("""
            select c.numero, extract(month from m.date_mouvement)::int as mois, sum(m.montant_usd) as montant
            from projet_mouvements m
            join projet_articles a on a.id = m.article_id
            join comptes_ohada c on c.id = a.compte_id
            where a.projet_id is null and extract(year from m.date_mouvement) = ?
            group by c.numero, mois
            """, rs -> {
            ajouter(enAttente, rs.getString("numero"), rs.getInt("mois"), rs.getBigDecimal("montant"));
        }, exercice);
        return new Execution(recu, enAttente);
    }

    private static void ajouter(Map<String, BigDecimal[]> parCompte, String compte, int mois, BigDecimal montant) {
        if (mois < 1 || mois > 12 || montant == null) return;
        BigDecimal[] serie = parCompte.computeIfAbsent(compte, k -> {
            BigDecimal[] z = new BigDecimal[12];
            java.util.Arrays.fill(z, BigDecimal.ZERO);
            return z;
        });
        serie[mois - 1] = serie[mois - 1].add(montant);
    }
}

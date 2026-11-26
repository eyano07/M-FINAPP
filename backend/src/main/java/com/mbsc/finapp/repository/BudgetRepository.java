package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findByStatut(StatutBudget statut);

    List<Budget> findByExercice(Integer exercice);

    @EntityGraph(attributePaths = {"lignes", "lignes.compte", "lignes.service", "elaborePar", "approuvePar"})
    Optional<Budget> findWithLignesById(Long id);

    List<Budget> findAllByOrderByExerciceDescDateCreationDesc();

    /**
     * Budgets d'un exercice dans un statut. En execution, ils ne couvrent jamais un meme mois (regle du service
     * et contrainte d'exclusion ex_budgets_un_en_execution_par_mois, V122) : un budget annuel, ou plusieurs
     * budgets de periodes disjointes (mois, trimestres, semestres).
     */
    @EntityGraph(attributePaths = {"lignes", "lignes.compte", "lignes.service"})
    List<Budget> findByExerciceAndStatut(Integer exercice, StatutBudget statut);

    boolean existsByRevisionDeId(Long budgetId);
}

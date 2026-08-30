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

    @EntityGraph(attributePaths = {"lignes", "lignes.compte", "elaborePar", "approuvePar"})
    Optional<Budget> findWithLignesById(Long id);

    List<Budget> findAllByOrderByExerciceDescDateCreationDesc();
}

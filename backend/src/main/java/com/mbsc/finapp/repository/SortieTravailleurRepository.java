package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.SortieTravailleur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SortieTravailleurRepository extends JpaRepository<SortieTravailleur, Long> {

    @Query("""
        SELECT s FROM SortieTravailleur s JOIN FETCH s.employe
        WHERE s.dateSortie BETWEEN :debut AND :fin
        ORDER BY s.dateSortie DESC, s.heureSortie DESC
        """)
    List<SortieTravailleur> findByPeriode(LocalDate debut, LocalDate fin);

    @Query("SELECT s FROM SortieTravailleur s JOIN FETCH s.employe ORDER BY s.dateSortie DESC, s.heureSortie DESC")
    List<SortieTravailleur> findAllOrdered();
}

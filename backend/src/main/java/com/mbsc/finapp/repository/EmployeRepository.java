package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Employe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmployeRepository extends JpaRepository<Employe, Long> {

    Optional<Employe> findByMatricule(String matricule);

    boolean existsByMatricule(String matricule);

    List<Employe> findByActifTrueOrderByNomComplet();

    List<Employe> findByConformeAndActifTrueOrderByNomComplet(boolean conforme);

    /** Superviseurs actifs, eligibles aux rotations de sites (module DRH_MISSIONS). */
    @Query("SELECT e FROM Employe e WHERE e.superviseur = true AND e.actif = true ORDER BY e.nomComplet")
    List<Employe> findSuperviseurs();

    @Query("""
        SELECT e FROM Employe e
        WHERE e.actif = true
          AND (LOWER(e.nomComplet) LIKE LOWER(CONCAT('%', :recherche, '%'))
               OR LOWER(e.matricule) LIKE LOWER(CONCAT('%', :recherche, '%')))
        ORDER BY e.nomComplet
        """)
    List<Employe> rechercher(String recherche);

    /** Plus grand suffixe numerique deja utilise dans un matricule "MBSC-NNN" (0 si aucun). */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(matricule FROM 'MBSC-(\\d+)') AS INTEGER)), 0) " +
        "FROM drh_employes WHERE matricule ~ '^MBSC-\\d+$'", nativeQuery = true)
    int dernierSuffixeMatricule();
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.RotationSuperviseur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RotationSuperviseurRepository extends JpaRepository<RotationSuperviseur, Long> {

    @Query("SELECT r FROM RotationSuperviseur r JOIN FETCH r.employe JOIN FETCH r.site " +
        "ORDER BY r.annee DESC, r.mois DESC, r.numeroCycle, r.employe.nomComplet")
    List<RotationSuperviseur> findAllOrdered();

    @Query("SELECT r FROM RotationSuperviseur r JOIN FETCH r.employe JOIN FETCH r.site " +
        "WHERE r.annee = :annee AND r.mois = :mois " +
        "ORDER BY r.site.nom, r.employe.nomComplet, r.numeroCycle")
    List<RotationSuperviseur> findByAnneeAndMois(int annee, int mois);

    Optional<RotationSuperviseur> findByEmployeIdAndSiteIdAndAnneeAndMoisAndNumeroCycle(
        Long employeId, Long siteId, int annee, int mois, int numeroCycle);

    /** Aucun autre agent ne peut avoir une prestation qui chevauche celle-ci sur le même site. */
    @Query("SELECT r FROM RotationSuperviseur r WHERE r.site.id = :siteId " +
        "AND r.prestationDebut <= :prestFin AND r.prestationFin >= :prestDebut " +
        "AND (:excludeId IS NULL OR r.id <> :excludeId)")
    List<RotationSuperviseur> findPrestationOverlapsOnSite(@Param("siteId") Long siteId,
                                                            @Param("prestDebut") LocalDate prestDebut,
                                                            @Param("prestFin") LocalDate prestFin,
                                                            @Param("excludeId") Long excludeId);
}

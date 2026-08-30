package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BulletinPaieRepository extends JpaRepository<BulletinPaie, Long> {

    Optional<BulletinPaie> findByEmployeIdAndMoisAndAnnee(Long employeId, Integer mois, Integer annee);

    @Query("""
        SELECT b FROM BulletinPaie b JOIN FETCH b.employe
        WHERE b.mois = :mois AND b.annee = :annee
        ORDER BY b.employe.nomComplet
        """)
    List<BulletinPaie> findByPeriode(Integer mois, Integer annee);

    @Query("""
        SELECT b FROM BulletinPaie b JOIN FETCH b.employe
        WHERE b.mois = :mois AND b.annee = :annee AND b.statut = :statut
        ORDER BY b.employe.nomComplet
        """)
    List<BulletinPaie> findByPeriodeAndStatut(Integer mois, Integer annee, StatutBulletin statut);

    @Query("SELECT b FROM BulletinPaie b JOIN FETCH b.employe WHERE b.employe.id = :employeId ORDER BY b.annee DESC, b.mois DESC")
    List<BulletinPaie> findByEmploye(Long employeId);
}

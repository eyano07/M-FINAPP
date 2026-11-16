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

    @Query("SELECT b FROM BulletinPaie b JOIN FETCH b.employe WHERE b.noteFraisPaie.id = :noteId ORDER BY b.employe.nomComplet")
    List<BulletinPaie> findByNoteFraisPaieId(Long noteId);

    @Query("SELECT b FROM BulletinPaie b JOIN FETCH b.employe WHERE b.employe.id = :employeId ORDER BY b.annee DESC, b.mois DESC")
    List<BulletinPaie> findByEmploye(Long employeId);

    /**
     * Avances déjà retenues sur les bulletins BROUILLON ou VALIDE d'un agent qui ne sont pas encore comptabilisés
     * (aucune pièce : la retenue n'a pas encore crédité son compte d'avances), hors le bulletin {@code exclu}
     * (-1 pour n'en exclure aucun). Audit DRH du 15/11/2026, D-06.
     */
    @Query("""
        SELECT COALESCE(SUM(b.avanceSalaire), 0) FROM BulletinPaie b
        WHERE b.employe.id = :employeId AND b.pieceComptable IS NULL AND b.id <> :exclu
          AND b.statut IN (com.mbsc.finapp.domain.enums.StatutBulletin.BROUILLON,
                           com.mbsc.finapp.domain.enums.StatutBulletin.VALIDE)
        """)
    java.math.BigDecimal avancesRetenuesNonComptabilisees(Long employeId, Long exclu);

    /** Bulletins VALIDE d'un agent pour une année (indemnités de congé déjà payées : provision pour congés). */
    @Query("""
        SELECT b FROM BulletinPaie b
        WHERE b.employe.id = :employeId AND b.annee = :annee
          AND b.statut = com.mbsc.finapp.domain.enums.StatutBulletin.VALIDE
        """)
    List<BulletinPaie> findValidesParEmployeEtAnnee(Long employeId, Integer annee);
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.SortiePlat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SortiePlatRepository extends JpaRepository<SortiePlat, Long> {

    @Query("""
        select s from SortiePlat s
        join fetch s.article
        join fetch s.entrepot
        join fetch s.mouvementStock m
        left join fetch m.piece
        left join fetch s.createdBy
        where s.dateSortie >= :du
          and s.dateSortie <= :au
        order by s.dateSortie desc, s.id desc
    """)
    List<SortiePlat> rechercher(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /** true si ce mouvement de stock est la sortie d'un plat : il ne s'annule qu'avec elle. */
    boolean existsByMouvementStockId(Long mouvementStockId);
}

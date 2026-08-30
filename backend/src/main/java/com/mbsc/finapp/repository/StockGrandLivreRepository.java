package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.StockGrandLivre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StockGrandLivreRepository extends JpaRepository<StockGrandLivre, Long> {

    @Query("""
        select s from StockGrandLivre s
        join fetch s.article a
        join fetch s.entrepot e
        left join fetch s.mouvement m
        where (:articleId is null or a.id = :articleId)
          and (:entrepotId is null or e.id = :entrepotId)
          and s.dateEcriture >= :du
          and s.dateEcriture <= :au
        order by s.dateEcriture asc, s.id asc
    """)
    List<StockGrandLivre> rechercher(@Param("articleId") Long articleId,
                                     @Param("entrepotId") Long entrepotId,
                                     @Param("du") LocalDate du,
                                     @Param("au") LocalDate au);
}

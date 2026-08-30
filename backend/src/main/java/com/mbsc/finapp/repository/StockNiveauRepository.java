package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.StockNiveau;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockNiveauRepository extends JpaRepository<StockNiveau, Long> {

    Optional<StockNiveau> findByArticleAndEntrepot(Article article, Entrepot entrepot);

    @Query("""
        select n from StockNiveau n
        join fetch n.article a
        join fetch n.entrepot e
        order by a.code, e.code
    """)
    List<StockNiveau> findAllWithDetails();
}

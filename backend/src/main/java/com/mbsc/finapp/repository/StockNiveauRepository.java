package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.StockNiveau;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Nettoyage lors de la suppression definitive d'un article (voir RestaurantService.supprimerArticleCarte). */
    void deleteByArticleId(Long articleId);

    /**
     * Ligne de stock verrouillee jusqu'a la fin de la transaction : deux caisses qui mettent de cote la derniere bouteille
     * en meme temps passent l'une apres l'autre (voir ReservationStockService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from StockNiveau n where n.article.id = :articleId and n.entrepot.id = :entrepotId")
    Optional<StockNiveau> verrouiller(@Param("articleId") Long articleId, @Param("entrepotId") Long entrepotId);
}

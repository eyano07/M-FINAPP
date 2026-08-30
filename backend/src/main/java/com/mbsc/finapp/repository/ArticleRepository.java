package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findByCode(String code);
    boolean existsByCode(String code);

    /** Retrouve un article de marchandise existant par son nom, pour eviter de dupliquer
     *  l'article a chaque nouvel achat de la meme marchandise via une note de frais. */
    Optional<Article> findFirstByLibelleIgnoreCaseAndType(String libelle, com.mbsc.finapp.domain.enums.TypeArticle type);

    @Query("""
        select a from Article a
        left join fetch a.compteStock
        left join fetch a.compteCharge
        left join fetch a.compteProduit
        left join fetch a.entrepot
        order by a.code
    """)
    List<Article> findAllWithComptes();
}

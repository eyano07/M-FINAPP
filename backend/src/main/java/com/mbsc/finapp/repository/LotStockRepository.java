package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LotStock;
import com.mbsc.finapp.domain.enums.TypeArticle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface LotStockRepository extends JpaRepository<LotStock, Long> {

    /** Lots encore actifs d'un (article, entrepôt), du plus ancien au plus récent — l'ordre du FIFO. */
    List<LotStock> findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(
        Long articleId, Long entrepotId, BigDecimal seuil);

    /** Lots encore actifs de tous les articles d'un type (BOISSON ou PROVISION), pour les pages de stock par lot. */
    List<LotStock> findByArticle_TypeAndQuantiteRestanteGreaterThanOrderByDateEntreeAsc(
        TypeArticle type, BigDecimal seuil);

    /** Lot(s) créés par une ligne de mouvement (entrée, ou arrivée d'un transfert) — pour son annulation. */
    List<LotStock> findByLigneMouvementId(Long ligneMouvementId);
}

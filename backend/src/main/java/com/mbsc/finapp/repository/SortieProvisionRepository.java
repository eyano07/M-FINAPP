package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.SortieProvision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SortieProvisionRepository extends JpaRepository<SortieProvision, Long> {

    /** Type et observation des sorties de ces mouvements de stock, en une seule requête pour tout l'historique. */
    @Query("select s from SortieProvision s where s.mouvementStock.id in :ids")
    List<SortieProvision> findByMouvementStockIds(@Param("ids") Collection<Long> ids);
}

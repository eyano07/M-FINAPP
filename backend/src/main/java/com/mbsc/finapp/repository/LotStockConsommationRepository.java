package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LotStockConsommation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LotStockConsommationRepository extends JpaRepository<LotStockConsommation, Long> {

    /** Ce qu'une ligne de sortie (ou le départ d'un transfert) a prélevé sur chaque lot — pour son annulation exacte. */
    List<LotStockConsommation> findByLigneMouvementId(Long ligneMouvementId);
}

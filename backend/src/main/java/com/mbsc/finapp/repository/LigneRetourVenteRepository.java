package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LigneRetourVente;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LigneRetourVenteRepository extends JpaRepository<LigneRetourVente, Long> {

    /** true si ce mouvement de stock est la remise en stock d'un retour de vente : il ne s'annule pas seul. */
    boolean existsByMouvementStockId(Long mouvementStockId);
}

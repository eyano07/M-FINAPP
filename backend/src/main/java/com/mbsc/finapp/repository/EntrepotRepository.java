package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Entrepot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntrepotRepository extends JpaRepository<Entrepot, Long> {
    Optional<Entrepot> findByCode(String code);
    boolean existsByCode(String code);
    List<Entrepot> findAllByOrderByCodeAsc();

    /** Entrepot par defaut retenu automatiquement pour une entree de stock quand
     *  l'utilisateur n'en choisit plus un explicitement (achat de marchandise via note de frais). */
    Optional<Entrepot> findFirstByActifTrueOrderByIdAsc();
}

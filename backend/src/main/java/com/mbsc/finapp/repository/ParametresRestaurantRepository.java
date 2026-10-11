package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ParametresRestaurant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ParametresRestaurantRepository extends JpaRepository<ParametresRestaurant, Long> {

    /**
     * Charge la ligne unique en la verrouillant jusqu'au commit. Sert de verrou à tout retrait de plats périmés, manuel ou
     * automatique : le calcul des portions périmées et l'écriture de leur sortie doivent se suivre sans qu'un autre retrait
     * s'intercale, sans quoi deux retraits simultanés (la tâche planifiée et un clic du responsable) retireraient chacun les
     * mêmes portions périmées et entameraient en plus le stock encore bon.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ParametresRestaurant p where p.id = :id")
    Optional<ParametresRestaurant> findByIdPourMiseAJour(Long id);
}

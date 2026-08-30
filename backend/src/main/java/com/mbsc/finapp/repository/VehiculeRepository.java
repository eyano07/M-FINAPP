package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Vehicule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehiculeRepository extends JpaRepository<Vehicule, Long> {
    Optional<Vehicule> findByImmatriculation(String immatriculation);
    boolean existsByImmatriculation(String immatriculation);
    List<Vehicule> findAllByOrderByImmatriculationAsc();
}

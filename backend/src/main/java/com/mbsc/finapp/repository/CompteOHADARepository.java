package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.CompteOHADA;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompteOHADARepository extends JpaRepository<CompteOHADA, Long> {
    Optional<CompteOHADA> findByNumero(String numero);
    boolean existsByNumero(String numero);
    List<CompteOHADA> findAllByOrderByNumeroAsc();
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.CompteOHADA;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CompteOHADARepository extends JpaRepository<CompteOHADA, Long> {
    Optional<CompteOHADA> findByNumero(String numero);
    boolean existsByNumero(String numero);
    List<CompteOHADA> findAllByOrderByNumeroAsc();

    /** Comptes de la comptabilité analytique de gestion (SYSCOHADA classe 9, 92 à 99). */
    @Query("select c from CompteOHADA c where c.classe = 9 and c.numero >= '92' and c.numero < '9A' order by c.numero")
    List<CompteOHADA> findAnalytiques();
}

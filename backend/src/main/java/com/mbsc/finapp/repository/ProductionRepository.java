package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Production;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductionRepository extends JpaRepository<Production, Long> {

    /** Historique d'une periode, la plus recente en premier. */
    @Query("""
        select distinct p from Production p
        left join fetch p.lignes l
        left join fetch l.provision
        join fetch p.plat
        join fetch p.entrepot
        left join fetch p.createdBy
        where p.dateProduction between :du and :au
        order by p.dateProduction desc, p.id desc
    """)
    List<Production> rechercherPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    @Query("""
        select p from Production p
        left join fetch p.lignes l
        left join fetch l.provision
        join fetch p.plat
        join fetch p.entrepot
        left join fetch p.mouvementSortie
        left join fetch p.mouvementEntree
        where p.id = :id
    """)
    Optional<Production> findAvecLignes(@Param("id") Long id);
}

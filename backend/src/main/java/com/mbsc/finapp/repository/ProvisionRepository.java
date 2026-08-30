package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Provision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProvisionRepository extends JpaRepository<Provision, Long> {

    @Query("""
        select p from Provision p
        left join fetch p.compteProvision
        left join fetch p.compteDotation
        left join fetch p.createdBy
        order by p.dateConstitution desc, p.id desc
    """)
    List<Provision> listerAvecComptes();

    @Query("""
        select distinct p from Provision p
        left join fetch p.reprises r
        left join fetch r.compteReprise
        left join fetch p.compteProvision
        left join fetch p.compteDotation
        where p.id = :id
    """)
    Optional<Provision> findWithReprisesById(@Param("id") Long id);
}

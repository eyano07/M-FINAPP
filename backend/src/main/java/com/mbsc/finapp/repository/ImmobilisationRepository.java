package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Immobilisation;
import com.mbsc.finapp.domain.enums.StatutImmobilisation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ImmobilisationRepository extends JpaRepository<Immobilisation, Long> {

    boolean existsByReference(String reference);

    @Query("""
        select distinct i from Immobilisation i
        left join fetch i.compteImmobilisation
        left join fetch i.compteAmortissement
        left join fetch i.compteDotation
        left join fetch i.responsable
        order by i.reference desc
        """)
    List<Immobilisation> findAllWithComptes();

    @Query("""
        select distinct i from Immobilisation i
        left join fetch i.planAmortissement
        left join fetch i.compteImmobilisation
        left join fetch i.compteAmortissement
        left join fetch i.compteDotation
        where i.id = :id
        """)
    Optional<Immobilisation> findWithPlanById(Long id);

    /** Biens dont les dotations peuvent encore etre passees. */
    @Query("""
        select distinct i from Immobilisation i
        left join fetch i.planAmortissement
        where i.statut = :statut
        """)
    List<Immobilisation> findWithPlanByStatut(StatutImmobilisation statut);
}

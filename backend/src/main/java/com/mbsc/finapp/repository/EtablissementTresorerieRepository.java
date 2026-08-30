package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EtablissementTresorerieRepository extends JpaRepository<EtablissementTresorerie, Long> {

    boolean existsByNomIgnoreCaseAndType(String nom, TypeEtablissement type);

    @Query("""
        select e from EtablissementTresorerie e
        join fetch e.compte
        where e.type = ?1
        order by e.nom asc
    """)
    List<EtablissementTresorerie> findByTypeAvecCompte(TypeEtablissement type);

    @Query("""
        select e from EtablissementTresorerie e
        join fetch e.compte
        order by e.type asc, e.nom asc
    """)
    List<EtablissementTresorerie> findAllAvecCompte();
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Trajet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TrajetRepository extends JpaRepository<Trajet, Long> {

    @Query("""
        select t from Trajet t
        join fetch t.vehicule v
        left join fetch t.conducteur
        order by t.dateDepart desc, t.id desc
    """)
    List<Trajet> findAllWithDetails();
}

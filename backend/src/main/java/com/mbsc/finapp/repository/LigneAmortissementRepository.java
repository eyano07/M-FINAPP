package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LigneAmortissement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LigneAmortissementRepository extends JpaRepository<LigneAmortissement, Long> {

    /** Dotations echues restant a comptabiliser, biens en service uniquement. */
    @Query("""
        select l from LigneAmortissement l
        join fetch l.immobilisation i
        where l.comptabilise = false
          and l.periode <= :jusqua
          and i.statut = com.mbsc.finapp.domain.enums.StatutImmobilisation.EN_SERVICE
        order by i.reference asc, l.periode asc
        """)
    List<LigneAmortissement> aComptabiliser(LocalDate jusqua);

    @Query("""
        select coalesce(sum(l.dotation), 0) from LigneAmortissement l
        where l.comptabilise = true and l.periode between :du and :au
        """)
    BigDecimal totalDotationsComptabilisees(LocalDate du, LocalDate au);
}

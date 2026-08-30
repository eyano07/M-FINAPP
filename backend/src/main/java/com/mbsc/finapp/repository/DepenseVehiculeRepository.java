package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.DepenseVehicule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface DepenseVehiculeRepository extends JpaRepository<DepenseVehicule, Long> {

    @Query("""
        select d from DepenseVehicule d
        join fetch d.vehicule v
        left join fetch d.trajet
        left join fetch d.compteCharge
        order by d.dateDepense desc, d.id desc
    """)
    List<DepenseVehicule> findAllWithDetails();

    @Query("select coalesce(sum(d.montant), 0) from DepenseVehicule d where d.vehicule.id = :vehiculeId")
    BigDecimal totalDepensesVehicule(@Param("vehiculeId") Long vehiculeId);
}

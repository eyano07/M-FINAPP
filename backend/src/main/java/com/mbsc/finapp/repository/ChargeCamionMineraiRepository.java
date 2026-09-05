package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ChargeCamionMinerai;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChargeCamionMineraiRepository extends JpaRepository<ChargeCamionMinerai, Long> {

    List<ChargeCamionMinerai> findByCamionIdOrderByDateChargeAscIdAsc(Long camionId);

    /** Charges dont la dette prestataire reste a solder, proposees au reglement en caisse. */
    List<ChargeCamionMinerai> findByRegleFalseOrderByDateChargeAscIdAsc();

    boolean existsByCamionId(Long camionId);
}

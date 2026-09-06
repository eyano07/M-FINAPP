package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ChargeCamionMinerai;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ChargeCamionMineraiRepository extends JpaRepository<ChargeCamionMinerai, Long> {

    List<ChargeCamionMinerai> findByCamionIdOrderByDateChargeAscIdAsc(Long camionId);

    /** Charges saisies pendant qu'un camion etait A_VALIDER : a incorporer une fois l'achat valide. */
    List<ChargeCamionMinerai> findByCamionIdAndPieceIsNull(Long camionId);

    /** Charges dont la dette prestataire reste a solder, proposees au reglement en caisse. */
    List<ChargeCamionMinerai> findByRegleFalseOrderByDateChargeAscIdAsc();

    boolean existsByCamionId(Long camionId);

    /** Total des frais connexes (postes ou en attente) par camion, pour les listes de camions. */
    @Query("SELECT ch.camion.id AS camionId, COALESCE(SUM(ch.montant), 0) AS total "
        + "FROM ChargeCamionMinerai ch WHERE ch.camion.id IN :camionIds GROUP BY ch.camion.id")
    List<TotalFraisParCamion> sommerParCamionIds(@Param("camionIds") List<Long> camionIds);

    interface TotalFraisParCamion {
        Long getCamionId();
        BigDecimal getTotal();
    }
}

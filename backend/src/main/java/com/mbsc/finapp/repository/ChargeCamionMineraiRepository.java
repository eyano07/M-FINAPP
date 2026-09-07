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

    /**
     * Charges dont la dette prestataire reste a solder, proposables a une
     * note de reglement. Postees (piece non nulle) : une charge encore en
     * attente (camion A_VALIDER) n'a pas de dette reelle a solder — elle en
     * aura une des que son camion sera valide, voir {@code
     * NoteFraisService.creerReglementCamionsMinerai}. Non deja rattachees a
     * une note en cours, pour la meme raison que {@code CamionMinerai
     * .noteFraisReglement}.
     */
    List<ChargeCamionMinerai> findByRegleFalseAndPieceIsNotNullAndNoteFraisReglementIsNullOrderByDateChargeAscIdAsc();

    /** Charges rattachees a une note de reglement donnee — pour les solder ou liberer si elle est annulee. */
    List<ChargeCamionMinerai> findByNoteFraisReglementId(Long noteFraisId);

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

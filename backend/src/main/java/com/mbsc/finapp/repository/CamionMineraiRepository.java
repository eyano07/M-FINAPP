package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CamionMineraiRepository extends JpaRepository<CamionMinerai, Long> {

    List<CamionMinerai> findAllByOrderByDateAchatDescIdDesc();

    List<CamionMinerai> findByArticleIdOrderByDateAchatDescIdDesc(Long articleId);

    /** Camions encore en stock, proposes a la vente. */
    List<CamionMinerai> findByArticleIdAndStatutOrderByDateAchatAscIdAsc(Long articleId, StatutCamionMinerai statut);

    /** Camions dont la dette fournisseur reste a solder, proposes au reglement en caisse. */
    List<CamionMinerai> findByRegleFalseOrderByDateAchatAscIdAsc();

    boolean existsByArticleIdAndPlaqueIgnoreCaseAndDateAchat(Long articleId, String plaque, LocalDate dateAchat);

    /**
     * true si une ligne de vente reference encore ce camion — y compris une
     * vente ANNULEE, dont les lignes sont conservees pour la piste d'audit. Le
     * camion est alors revenu EN_STOCK et parait supprimable, mais la cle
     * etrangere l'interdit : sans ce garde-fou, la suppression remontait une
     * erreur 500 brute au lieu d'un message comprehensible.
     */
    @Query("select count(l) > 0 from LigneVente l where l.camion.id = :camionId")
    boolean estReferenceParUneVente(@Param("camionId") Long camionId);
}

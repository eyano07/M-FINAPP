package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CamionMineraiRepository extends JpaRepository<CamionMinerai, Long> {

    List<CamionMinerai> findAllByOrderByDateReceptionDescIdDesc();

    List<CamionMinerai> findByArticleIdOrderByDateReceptionDescIdDesc(Long articleId);

    /** Camions d'un statut donne (A_VALIDER, EN_STOCK...), les plus anciens d'abord. */
    List<CamionMinerai> findByArticleIdAndStatutOrderByDateReceptionAscIdAsc(Long articleId, StatutCamionMinerai statut);

    /** Camions dont la dette fournisseur reste a solder (validee ou non), proposes au
     * reglement en caisse ou a une nouvelle note de reglement — voir MineraiService.listerARegler.
     * Un camion deja rattache a une note de reglement en cours est exclu : il ne doit pas
     * pouvoir etre inclus deux fois (caisse directe et note, ou deux notes). */
    List<CamionMinerai> findByRegleFalseAndNoteFraisReglementIsNullOrderByDateReceptionAscIdAsc();

    /** Camions rattaches a une note de reglement donnee — pour la solder ou liberer les camions si elle est annulee. */
    List<CamionMinerai> findByNoteFraisReglementId(Long noteFraisId);

    boolean existsByArticleIdAndPlaqueIgnoreCaseAndDateReception(Long articleId, String plaque, LocalDate dateReception);

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

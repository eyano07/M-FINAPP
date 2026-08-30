package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.OrdreMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrdreMissionRepository extends JpaRepository<OrdreMission, Long> {

    @Query("SELECT o FROM OrdreMission o ORDER BY o.dateDepart DESC, o.id DESC")
    java.util.List<OrdreMission> findAllOrderByDateDepartDesc();

    /**
     * Réservation atomique du prochain numéro d'ordre de mission pour un
     * mois donné (upsert incrémental via CTE) : contrairement à un simple
     * {@code COUNT(*)+1}, ne réutilise jamais un numéro déjà attribué même
     * après suppression d'un ordre — bug connu de l'ancien outil PayMBSC.
     */
    @Query(value = """
        WITH ins AS (
            INSERT INTO drh_sequence_ordre_mission (annee, mois, dernier_numero)
            VALUES (:annee, :mois, 1)
            ON CONFLICT (annee, mois) DO UPDATE
                SET dernier_numero = drh_sequence_ordre_mission.dernier_numero + 1
            RETURNING dernier_numero
        )
        SELECT dernier_numero FROM ins
        """, nativeQuery = true)
    int prochainNumeroSequence(@Param("annee") int annee, @Param("mois") int mois);
}

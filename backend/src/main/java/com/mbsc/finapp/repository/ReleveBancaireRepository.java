package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ReleveBancaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReleveBancaireRepository extends JpaRepository<ReleveBancaire, Long> {

    boolean existsByEtablissementId(Long etablissementId);

    Optional<ReleveBancaire> findByEtablissementIdAndAnneeAndMois(Long etablissementId, Integer annee, Integer mois);

    @Query("select r from ReleveBancaire r join fetch r.etablissement where r.annee = :annee order by r.mois")
    List<ReleveBancaire> findByAnnee(@Param("annee") Integer annee);

    /** Relevés validés du compte : verrou des écritures datées d'un mois rapproché. */
    @Query("""
        select r from ReleveBancaire r
        where r.etablissement.compte.id = :compteId
          and r.statut = com.mbsc.finapp.domain.enums.StatutReleve.VALIDE
        order by r.annee desc, r.mois desc
        """)
    List<ReleveBancaire> validesDuCompte(@Param("compteId") Long compteId);

    /** Relevé précédent d'un établissement (contrôle de continuité des soldes). */
    @Query("""
        select r from ReleveBancaire r
        where r.etablissement.id = :etablissementId and (r.annee * 12 + r.mois) < (:annee * 12 + :mois)
        order by r.annee desc, r.mois desc
        """)
    List<ReleveBancaire> precedents(@Param("etablissementId") Long etablissementId, @Param("annee") Integer annee,
                                    @Param("mois") Integer mois);
}

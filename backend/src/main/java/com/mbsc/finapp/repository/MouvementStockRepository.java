package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.MouvementStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MouvementStockRepository extends JpaRepository<MouvementStock, Long> {

    /**
     * Receptions de boissons validees sur une periode — alimente le tableau
     * de bord du module Restaurant (quantites et montants achetes). Ne
     * retient que les ENTREE : une reception de boissons n'est jamais une
     * SORTIE ni un TRANSFERT.
     */
    @Query("""
        select distinct m from MouvementStock m
        join fetch m.lignes l
        join fetch l.article a
        where m.type = com.mbsc.finapp.domain.enums.TypeMouvementStock.ENTREE
          and m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
          and m.dateMouvement between :du and :au
          and a.type = com.mbsc.finapp.domain.enums.TypeArticle.BOISSON
    """)
    List<MouvementStock> receptionsBoissonsPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /** Pendant de {@link #receptionsBoissonsPeriode} pour les provisions (vivres, épices, charbon...). */
    @Query("""
        select distinct m from MouvementStock m
        join fetch m.lignes l
        join fetch l.article a
        where m.type = com.mbsc.finapp.domain.enums.TypeMouvementStock.ENTREE
          and m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
          and m.dateMouvement between :du and :au
          and a.type = com.mbsc.finapp.domain.enums.TypeArticle.PROVISION
    """)
    List<MouvementStock> receptionsProvisionsPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /**
     * Sorties de provisions validees sur une periode (utilisation en cuisine,
     * casse, peremption) — alimente le tableau de bord Provisions.
     */
    @Query("""
        select distinct m from MouvementStock m
        join fetch m.lignes l
        join fetch l.article a
        where m.type = com.mbsc.finapp.domain.enums.TypeMouvementStock.SORTIE
          and m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
          and m.dateMouvement between :du and :au
          and a.type = com.mbsc.finapp.domain.enums.TypeArticle.PROVISION
    """)
    List<MouvementStock> sortiesProvisionsPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    @Query("""
        select distinct m from MouvementStock m
        left join fetch m.lignes l
        left join fetch l.article
        left join fetch l.entrepotSource
        left join fetch l.entrepotCible
        left join fetch m.createdBy
        where m.id = :id
    """)
    Optional<MouvementStock> findWithLignesById(@Param("id") Long id);

    @Query("""
        select m from MouvementStock m
        left join fetch m.createdBy
        order by m.dateMouvement desc, m.id desc
    """)
    List<MouvementStock> findAllWithCreatedBy();
}

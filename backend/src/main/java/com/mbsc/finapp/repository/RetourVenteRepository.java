package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.RetourVente;
import com.mbsc.finapp.domain.enums.StatutRetourVente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RetourVenteRepository extends JpaRepository<RetourVente, Long> {

    @Query("""
        select distinct r from RetourVente r
        join fetch r.vente v
        join fetch r.demandeur
        left join fetch r.lignes l
        left join fetch l.ligneVente lv
        left join fetch lv.article
        where r.id = :id
    """)
    Optional<RetourVente> findAvecLignes(@Param("id") Long id);

    @Query("""
        select distinct r from RetourVente r
        join fetch r.vente v
        join fetch r.demandeur
        left join fetch r.lignes l
        left join fetch l.ligneVente lv
        left join fetch lv.article
        where v.id = :venteId
        order by r.id desc
    """)
    List<RetourVente> findParVente(@Param("venteId") Long venteId);

    @Query("""
        select distinct r from RetourVente r
        join fetch r.vente v
        join fetch r.demandeur
        left join fetch r.lignes l
        left join fetch l.ligneVente lv
        left join fetch lv.article
        where r.statut in :statuts
        order by r.id desc
    """)
    List<RetourVente> findParStatuts(@Param("statuts") Collection<StatutRetourVente> statuts);

    boolean existsByVenteIdAndStatutIn(Long venteId, Collection<StatutRetourVente> statuts);

    /**
     * Ce qui est deja rendu (ou en cours de retour) par ligne de vente : [id de la ligne de vente, quantite, HT, TVA]. Sert a
     * plafonner un nouveau retour et a donner au dernier retour d'une ligne le reste exact de ses montants (pas d'ecart
     * d'arrondi).
     */
    @Query("""
        select l.ligneVente.id, sum(l.quantite), sum(l.montantHt), sum(l.montantTva) from LigneRetourVente l
        where l.retour.vente.id = :venteId and l.retour.statut in :statuts
        group by l.ligneVente.id
    """)
    List<Object[]> cumulsParLigne(@Param("venteId") Long venteId, @Param("statuts") Collection<StatutRetourVente> statuts);

    /** Retours rembourses par diminution de la creance d'une vente a credit : ils reduisent ce qui reste a encaisser. */
    @Query("""
        select r from RetourVente r join fetch r.piece
        where r.vente.id = :venteId
          and r.statut = com.mbsc.finapp.domain.enums.StatutRetourVente.REMBOURSE
          and r.canal = com.mbsc.finapp.domain.enums.CanalRemboursement.CREANCE
    """)
    List<RetourVente> remboursementsSurCreance(@Param("venteId") Long venteId);
}

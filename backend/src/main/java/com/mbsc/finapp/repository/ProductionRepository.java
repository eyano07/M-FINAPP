package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Production;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductionRepository extends JpaRepository<Production, Long> {

    /** Historique d'une periode, la plus recente en premier. */
    @Query("""
        select distinct p from Production p
        left join fetch p.lignes l
        left join fetch l.provision
        join fetch p.plat
        join fetch p.entrepot
        left join fetch p.createdBy
        where p.dateProduction between :du and :au
        order by p.dateProduction desc, p.id desc
    """)
    List<Production> rechercherPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    @Query("""
        select p from Production p
        left join fetch p.lignes l
        left join fetch l.provision
        join fetch p.plat
        join fetch p.entrepot
        left join fetch p.mouvementSortie
        left join fetch p.mouvementEntree
        where p.id = :id
    """)
    Optional<Production> findAvecLignes(@Param("id") Long id);

    /** Un plat deja produit ne peut pas etre supprime definitivement (historique de production). */
    boolean existsByPlatId(Long platId);

    /** Une provision deja consommee par une production ne peut pas etre supprimee definitivement. */
    @Query("select count(l) > 0 from LigneProduction l where l.provision.id = :provisionId")
    boolean existsLigneAvecProvision(@Param("provisionId") Long provisionId);

    /**
     * Reference de la production dont ce mouvement est la sortie des
     * ingredients ou l'entree des portions, s'il y en a une. Jointures
     * externes explicites : une production peut n'avoir que l'un des deux.
     */
    @Query("""
        select p.reference from Production p
        left join p.mouvementSortie ms
        left join p.mouvementEntree me
        where ms.id = :mouvementId or me.id = :mouvementId
    """)
    List<String> referencesParMouvement(@Param("mouvementId") Long mouvementId);

    /** Mouvements valides appartenant a une production (sortie des ingredients ou entree des portions). */
    @Query("""
        select m.id from Production p join p.mouvementSortie m
        where m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
    """)
    List<Long> idsMouvementsSortieValides();

    @Query("""
        select m.id from Production p join p.mouvementEntree m
        where m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
    """)
    List<Long> idsMouvementsEntreeValides();

    /** Parmi ces mouvements, ceux qui sont la sortie des ingredients d'une production. */
    @Query("select m.id from Production p join p.mouvementSortie m where m.id in :ids")
    List<Long> idsMouvementsSortieParmi(@Param("ids") Collection<Long> ids);

    /** Parmi ces mouvements, ceux qui sont l'entree des portions d'une production. */
    @Query("select m.id from Production p join p.mouvementEntree m where m.id in :ids")
    List<Long> idsMouvementsEntreeParmi(@Param("ids") Collection<Long> ids);
}

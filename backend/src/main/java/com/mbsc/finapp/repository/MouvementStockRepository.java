package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.MouvementStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MouvementStockRepository extends JpaRepository<MouvementStock, Long> {

    /**
     * true si cette pièce comptable appartient à un mouvement de stock — sa
     * pièce de stock, la pièce d'achat d'une réception directe de provision,
     * ou la pièce d'écart constatée à son annulation. Utilisé pour refuser
     * son extourne directe depuis l'écran Comptabilité (voir
     * ComptabiliteService.annuler) : un mouvement de stock (donc, par
     * transitivité, une production, qui n'a pas de pièce propre mais deux
     * mouvements) ne doit être extourné que par son écran métier
     * (Logistique, Vente, Production ou Restaurant), seul capable de rejouer
     * le niveau de stock et les statuts associés en cohérence.
     *
     * <p>Jointures externes explicites : chacune de ces pièces est
     * facultative, et un chemin implicite ({@code m.pieceAchat.id}) peut être
     * traduit en jointure interne, qui écarterait tout mouvement n'ayant pas
     * les trois.</p>
     */
    @Query("""
        select count(m) > 0 from MouvementStock m
        left join m.piece p
        left join m.pieceAchat pa
        left join m.pieceEcartAnnulation pe
        where p.id = :pieceId or pa.id = :pieceId or pe.id = :pieceId
    """)
    boolean existsAvecPieceId(@Param("pieceId") Long pieceId);

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

    /**
     * Sorties de boissons validees sur une periode : ventes, casse d'une
     * bouteille pleine, peremption, cadeau — toutes passent par une SORTIE de
     * stock valorisee au CMP du moment ({@code StockService.appliquerLigne}).
     *
     * <p>Alimente le cout des ventes du tableau de bord Restaurant, qui
     * l'estimait auparavant au CMP <i>courant</i> : le cout historique reel
     * est deja porte par {@code LigneMouvementStock.montant}, il suffit de le
     * lire plutot que de le recalculer.</p>
     */
    @Query("""
        select distinct m from MouvementStock m
        join fetch m.lignes l
        join fetch l.article a
        where m.type = com.mbsc.finapp.domain.enums.TypeMouvementStock.SORTIE
          and m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
          and m.dateMouvement between :du and :au
          and a.type = com.mbsc.finapp.domain.enums.TypeArticle.BOISSON
    """)
    List<MouvementStock> sortiesBoissonsPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

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

    /**
     * Mouvements valides portant sur au moins un plat, une boisson ou une
     * provision — articles du module Restaurant, que seul l'administrateur
     * peut corriger depuis la Logistique (voir StockService.listerMouvements).
     */
    @Query("""
        select distinct m.id from LigneMouvementStock l
        join l.mouvement m
        join l.article a
        where m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
          and a.type in (com.mbsc.finapp.domain.enums.TypeArticle.PLAT,
                         com.mbsc.finapp.domain.enums.TypeArticle.BOISSON,
                         com.mbsc.finapp.domain.enums.TypeArticle.PROVISION)
    """)
    List<Long> idsValidesModuleRestaurant();

    /** Un seul mouvement citant cet article suffit a interdire sa suppression definitive. */
    @Query("select count(l) > 0 from LigneMouvementStock l where l.article.id = :articleId")
    boolean existsLigneAvecArticle(@Param("articleId") Long articleId);

    /**
     * Recuperation groupee (fetch join) des mouvements et de leurs lignes,
     * pour reconstituer le cout historique reel de chaque vente sans
     * requete par ligne — voir RestaurantService.analyserVentes.
     */
    @Query("""
        select distinct m from MouvementStock m
        left join fetch m.lignes l
        left join fetch l.article
        where m.id in :ids
    """)
    List<MouvementStock> findAllByIdInWithLignes(@Param("ids") Collection<Long> ids);
}

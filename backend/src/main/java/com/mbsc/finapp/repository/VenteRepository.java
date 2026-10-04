package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.Vente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VenteRepository extends JpaRepository<Vente, Long> {

    /**
     * true si cette pièce comptable est référencée par une vente (sa propre
     * pièce de vente, son règlement de créance, ou une réévaluation) —
     * utilisé pour refuser son extourne directe depuis l'écran Comptabilité
     * (voir ComptabiliteService.annuler) : seule VenteService sait rejouer
     * correctement le statut de la vente en cohérence avec l'extourne.
     */
    @Query("""
        select count(v) > 0 from Vente v
        left join v.piece p
        left join v.pieceReglement pr
        left join v.pieceReevaluation pe
        where p.id = :pieceId or pr.id = :pieceId or pe.id = :pieceId
    """)
    boolean existsAvecPieceId(@Param("pieceId") Long pieceId);

    /** Référence de la vente dont ce mouvement est la sortie de stock, s'il y en a une. */
    @Query("select v.reference from Vente v join v.mouvement m where m.id = :mouvementId")
    List<String> referencesParMouvement(@Param("mouvementId") Long mouvementId);

    /** Mouvements valides qui sont la sortie de stock d'une vente. */
    @Query("""
        select m.id from Vente v join v.mouvement m
        where m.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
    """)
    List<Long> idsMouvementsValides();

    /** Parmi ces mouvements, ceux qui sont la sortie de stock d'une vente. */
    @Query("select m.id from Vente v join v.mouvement m where m.id in :ids")
    List<Long> idsMouvementsParmi(@Param("ids") Collection<Long> ids);

    /**
     * Lignes de vente de boissons validees sur une periode — alimente le
     * tableau de bord du module Restaurant (chiffre d'affaires, quantites
     * vendues, classement des boissons). Une vente BROUILLON n'a encore
     * produit ni stock ni recette reelle, elle est donc exclue.
     */
    @Query("""
        select l from LigneVente l
        join fetch l.vente v
        join fetch l.article a
        where v.statut = com.mbsc.finapp.domain.enums.StatutVente.VALIDEE
          and v.dateVente between :du and :au
          and a.type = com.mbsc.finapp.domain.enums.TypeArticle.BOISSON
        order by v.dateVente
    """)
    List<LigneVente> ligneVentesBoissonsPeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /** Detail complet : lignes, articles et rattachements comptables. */
    @Query("""
        select distinct v from Vente v
        left join fetch v.lignes l
        left join fetch l.article
        left join fetch v.client
        left join fetch v.etablissement
        left join fetch v.entrepot
        left join fetch v.piece
        left join fetch v.createdBy
        where v.id = :id
    """)
    Optional<Vente> findWithLignesById(@Param("id") Long id);

    /** Liste allegee : pas de lignes, pour l'ecran de suivi. */
    @Query("""
        select v from Vente v
        left join fetch v.client
        left join fetch v.etablissement
        left join fetch v.createdBy
        order by v.dateVente desc, v.id desc
    """)
    List<Vente> findAllPourListe();

    /**
     * Creances clients encore ouvertes, toutes devises confondues. Le filtre
     * "devise != devise de base" se fait en Java (voir appelant) plutot
     * qu'ici en dur : la devise de base peut changer (elle l'a deja fait une
     * fois cette annee), et une comparaison figee dans le JPQL serait le
     * meme piege que celui deja corrige ailleurs dans l'application.
     */
    @Query("""
        select v from Vente v
        left join fetch v.piece
        left join fetch v.pieceReevaluation
        where v.statut = com.mbsc.finapp.domain.enums.StatutVente.VALIDEE
          and v.modeReglement = com.mbsc.finapp.domain.enums.ModeReglement.CREDIT
          and v.pieceReglement is null
    """)
    List<Vente> creancesOuvertes();

    /**
     * Ventes non annulees rattachees a une ou plusieurs tables du restaurant —
     * sert a la fois a lister les commandes d'une table precise et a deriver
     * le badge paye/non-paye affiche sur le plan (voir RestaurantService).
     * Une vente ANNULEE est exclue : elle ne represente plus une commande en
     * cours, contrairement a un BROUILLON (deja engagee, en cours de saisie).
     */
    @Query("""
        select v from Vente v
        left join fetch v.client
        left join fetch v.table
        where v.table.id in :tableIds
          and v.statut != com.mbsc.finapp.domain.enums.StatutVente.ANNULEE
        order by v.createdAt desc
    """)
    List<Vente> findActivesByTableIdIn(@Param("tableIds") Collection<Long> tableIds);

    /**
     * Commandes d'une table avec tout ce que leur affichage lit (lignes,
     * articles, client, pieces...) en une requete : charges un a un, ces
     * rattachements coutaient six requetes par commande sur le plan de salle.
     */
    @Query("""
        select distinct v from Vente v
        left join fetch v.lignes l
        left join fetch l.article
        left join fetch v.client
        left join fetch v.etablissement
        left join fetch v.entrepot
        left join fetch v.table t
        left join fetch v.piece
        left join fetch v.mouvement
        left join fetch v.createdBy
        where t.id = :tableId
          and v.statut != com.mbsc.finapp.domain.enums.StatutVente.ANNULEE
        order by v.createdAt desc
    """)
    List<Vente> findActivesAvecDetailsByTableId(@Param("tableId") Long tableId);

    /**
     * Variante SANS l'exclusion des ventes ANNULEE : a utiliser avant de
     * supprimer une table ou une salle, pour detacher absolument toutes les
     * ventes qui y sont rattachees. {@link #findActivesByTableIdIn} ne
     * suffirait pas ici : une vente ANNULEE garderait sinon indefiniment son
     * table_id, provoquant une violation de contrainte de cle etrangere
     * (HTTP 500) a la suppression de la table.
     */
    @Query("""
        select v from Vente v
        where v.table.id in :tableIds
        order by v.createdAt desc
    """)
    List<Vente> findAllByTableIdIn(@Param("tableIds") Collection<Long> tableIds);

    /** Une seule vente citant cet article suffit a interdire sa suppression definitive. */
    @Query("select count(l) > 0 from LigneVente l where l.article.id = :articleId")
    boolean existsLigneAvecArticle(@Param("articleId") Long articleId);

    /**
     * Lignes de vente de plats ET boissons validees sur une periode —
     * alimente l'analyse des ventes (meilleures/moins bonnes ventes, marge
     * par article). Contrairement a {@link #ligneVentesBoissonsPeriode},
     * couvre toute la carte ; le mouvement de sortie est fetch-joint pour
     * retrouver le cout historique reel sans requete supplementaire par
     * ligne (voir RestaurantService.analyserVentes).
     */
    @Query("""
        select l from LigneVente l
        join fetch l.vente v
        join fetch l.article a
        left join fetch v.mouvement
        where v.statut = com.mbsc.finapp.domain.enums.StatutVente.VALIDEE
          and v.dateVente between :du and :au
          and a.type in (com.mbsc.finapp.domain.enums.TypeArticle.PLAT, com.mbsc.finapp.domain.enums.TypeArticle.BOISSON)
        order by v.dateVente
    """)
    List<LigneVente> ligneVentesCartePeriode(@Param("du") LocalDate du, @Param("au") LocalDate au);
}

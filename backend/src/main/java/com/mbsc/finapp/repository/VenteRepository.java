package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.Vente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface VenteRepository extends JpaRepository<Vente, Long> {

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
}

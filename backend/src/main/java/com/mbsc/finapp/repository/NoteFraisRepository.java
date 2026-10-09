package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.StatutNote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NoteFraisRepository extends JpaRepository<NoteFrais, Long> {

    List<NoteFrais> findByStatut(StatutNote statut);

    List<NoteFrais> findByCreateurId(Long createurId);

    boolean existsByReference(String reference);

    Optional<NoteFrais> findByReference(String reference);

    /** Notes de paie et notes fiscales d'un mois (module DRH), toutes catégories et statuts confondus. */
    @Query("""
        select n from NoteFrais n
        where n.categorie in (com.mbsc.finapp.domain.enums.CategorieNote.PAIE,
                              com.mbsc.finapp.domain.enums.CategorieNote.IMPOT_PAIE)
          and n.paieMois = :mois and n.paieAnnee = :annee
        order by n.dateCreation
        """)
    List<NoteFrais> findNotesPaie(@Param("mois") Integer mois, @Param("annee") Integer annee);

    /**
     * Charge la note avec ses observations et auteurs pour la vue detaillee.
     * Les lignes et pieces jointes (autres collections @OneToMany) sont
     * chargees paresseusement a l'acces : les combiner dans le meme
     * EntityGraph provoquerait un MultipleBagFetchException (plusieurs
     * collections "bag" ne peuvent pas etre fetch-join dans la meme
     * requete). Le cout (2 requetes supplementaires) est negligeable pour
     * une consultation unitaire.
     */
    @EntityGraph(attributePaths = {"observations", "observations.auteur", "createur"})
    Optional<NoteFrais> findWithDetailsById(Long id);

    /** Notes ordonnees par date de creation decroissante (vue liste). */
    List<NoteFrais> findAllByOrderByDateCreationDesc();

    /**
     * Nombre de notes TRANSMISE_CAISSE ayant la priorité donnée,
     * en excluant la note identifiée par {@code excludeId}.
     */
    @Query("""
        select count(n) from NoteFrais n
        where n.statut = com.mbsc.finapp.domain.enums.StatutNote.TRANSMISE_CAISSE
          and n.priorite = :priorite
          and n.id <> :excludeId
    """)
    long countPendingByPriority(@Param("priorite") PrioriteNote priorite,
                                @Param("excludeId") Long excludeId);

    /** Une seule ligne de note de frais citant cet article suffit a interdire sa suppression definitive. */
    @Query("select count(l) > 0 from LigneNoteFrais l where l.article.id = :articleId")
    boolean existsLigneAvecArticle(@Param("articleId") Long articleId);

    /**
     * Notes de decaissement d'une periode dans les statuts donnes, avec leurs lignes et comptes : engagements
     * budgetaires (notes approuvees, pas encore payees) du suivi et du controle budgetaire.
     */
    @Query("""
        select distinct n from NoteFrais n
        left join fetch n.lignes l
        left join fetch l.compteImputation
        where n.statut in :statuts
          and n.sens = com.mbsc.finapp.domain.enums.SensTransaction.DECAISSEMENT
          and n.dateCreation >= :du and n.dateCreation < :au
    """)
    List<NoteFrais> findDecaissementsAvecLignes(@Param("statuts") java.util.Collection<com.mbsc.finapp.domain.enums.StatutNote> statuts,
                                                @Param("du") java.time.Instant du, @Param("au") java.time.Instant au);

    /** Notes d'une periode dont le controle budgetaire a la soumission exigeait une justification (non annulees). */
    @Query("""
        select n from NoteFrais n
        left join fetch n.createur
        where n.statutBudget in :statuts
          and n.statut <> com.mbsc.finapp.domain.enums.StatutNote.ANNULEE
          and n.dateCreation >= :du and n.dateCreation < :au
        order by n.dateCreation desc
    """)
    List<NoteFrais> findHorsBudgetEntre(@Param("statuts") java.util.Collection<com.mbsc.finapp.domain.enums.StatutControleBudget> statuts,
                                         @Param("du") java.time.Instant du, @Param("au") java.time.Instant au);
}

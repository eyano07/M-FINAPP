package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.enums.JournalComptable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PieceComptableRepository extends JpaRepository<PieceComptable, Long> {

    @Query("""
        select p from PieceComptable p
        left join fetch p.lignes l
        left join fetch l.compte
        left join fetch p.createdBy
        where p.id = :id
    """)
    Optional<PieceComptable> findWithLignesById(@Param("id") Long id);

    @Query("""
        select p from PieceComptable p
        left join fetch p.createdBy
        order by p.datePiece desc, p.id desc
    """)
    List<PieceComptable> findAllWithCreatedByOrderByDatePieceDesc();

    /**
     * Livre-journal : pièces comptabilisées et annulées d'une période, avec
     * leurs lignes et comptes, triées chronologiquement (obligation OHADA).
     * Les brouillons sont exclus.
     */
    @Query("""
        select distinct p from PieceComptable p
        left join fetch p.lignes l
        left join fetch l.compte
        where p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON
          and p.datePiece >= :du and p.datePiece <= :au
        order by p.datePiece asc, p.id asc
    """)
    List<PieceComptable> livreJournal(@Param("du") java.time.LocalDate du,
                                      @Param("au") java.time.LocalDate au);

    /**
     * Livre-journal restreint à un seul journal (ex. CAISSE) : utilisé pour
     * la vue "journal de caisse" du caissier, qui ne doit pas voir les
     * pièces des autres journaux (achats, ventes, opérations diverses...).
     */
    @Query("""
        select distinct p from PieceComptable p
        left join fetch p.lignes l
        left join fetch l.compte
        where p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON
          and p.journal = :journal
          and p.datePiece >= :du and p.datePiece <= :au
        order by p.datePiece asc, p.id asc
    """)
    List<PieceComptable> livreJournalParJournal(@Param("journal") JournalComptable journal,
                                                 @Param("du") java.time.LocalDate du,
                                                 @Param("au") java.time.LocalDate au);

    /**
     * Pièces comptabilisées ayant mouvementé un compte donné (préfixe), tous
     * journaux confondus. Contrairement à {@link #livreJournalParJournal},
     * qui ne retrouve que les pièces explicitement étiquetées d'un journal —
     * absent pour tout ce qui vient d'un import ou d'une pièce manuelle —
     * cette requête reflète le compte réellement mouvementé : c'est ce que
     * l'utilisateur attend d'un « journal de caisse/banque/mobile money »
     * quelle que soit l'origine de l'écriture.
     */
    @Query("""
        select distinct p from PieceComptable p
        left join fetch p.lignes l
        left join fetch l.compte
        where p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON
          and p.datePiece >= :du and p.datePiece <= :au
          and exists (
              select 1 from EcritureGrandLivre e2
              join e2.compte c2
              where e2.piece = p and c2.numero like concat(:prefixeCompte, '%')
          )
        order by p.datePiece asc, p.id asc
    """)
    List<PieceComptable> livreJournalParCompte(@Param("prefixeCompte") String prefixeCompte,
                                               @Param("du") java.time.LocalDate du,
                                               @Param("au") java.time.LocalDate au);

    /**
     * Detection de double-soumission (A-07) : une piece manuelle identique
     * (meme auteur, journal, date, libelle et totaux) creee dans les
     * dernieres secondes signale presque toujours un double-clic ou une
     * re-soumission reseau plutot qu'une intention reelle de dupliquer.
     */
    @Query("""
        select count(p) from PieceComptable p
        where p.createdBy.id = :auteurId
          and p.journal = :journal
          and p.datePiece = :datePiece
          and p.libelle = :libelle
          and p.totalDebit = :totalDebit
          and p.createdAt >= :depuis
    """)
    long compterRecentesIdentiques(@Param("auteurId") Long auteurId,
                                    @Param("journal") JournalComptable journal,
                                    @Param("datePiece") java.time.LocalDate datePiece,
                                    @Param("libelle") String libelle,
                                    @Param("totalDebit") java.math.BigDecimal totalDebit,
                                    @Param("depuis") java.time.Instant depuis);

    /** Efface les pieces devenues vides apres suppression de leurs ecritures. */
    @Modifying
    @Query("""
        delete from PieceComptable p
        where not exists (select 1 from EcritureGrandLivre e where e.piece = p)
        """)
    int supprimerPiecesSansEcriture();
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.EcritureGrandLivre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Accès au Grand Livre.
 *
 * <p>Toutes les requêtes d'agrégation et de restitution excluent les écritures
 * rattachées à une pièce encore au brouillon ({@code StatutPiece.BROUILLON}) :
 * comme dans Sage, le brouillard n'impacte aucun état tant qu'il n'est pas
 * validé. Les écritures sans pièce (flux hérités) et les pièces comptabilisées
 * ou annulées (dont l'extourne compense l'origine) restent prises en compte.</p>
 *
 * <p>NB : le filtre utilise un {@code left join} explicite sur la pièce ;
 * une navigation implicite ({@code e.piece.statut}) produirait un inner join
 * qui éliminerait à tort les écritures sans pièce.</p>
 */
public interface EcritureGrandLivreRepository extends JpaRepository<EcritureGrandLivre, Long> {

    /**
     * Rapprochement bancaire : écritures d'un compte (hors brouillons, même convention que {@link #soldePourCompte}) jusqu'à une date, encore à pointer ou
     * pointées sur le relevé donné (les écritures pointées sur un autre relevé sont exclues).
     */
    @Query("""
        select e from EcritureGrandLivre e
        left join fetch e.piece p
        left join fetch e.pointage pt
        where e.compte.id = :compteId and e.dateEcriture <= :jusquAu
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
          and (pt is null or pt.releve.id = :releveId)
        order by e.dateEcriture, e.id
        """)
    List<EcritureGrandLivre> pourRapprochement(@Param("compteId") Long compteId, @Param("jusquAu") LocalDate jusquAu,
                                               @Param("releveId") Long releveId);

    /** Toutes les écritures d'un compte jusqu'à une date, hors brouillons (solde comptable du rapprochement). */
    @Query("""
        select e from EcritureGrandLivre e left join e.piece p
        where e.compte.id = :compteId and e.dateEcriture <= :jusquAu
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        """)
    List<EcritureGrandLivre> duCompteJusquAu(@Param("compteId") Long compteId, @Param("jusquAu") LocalDate jusquAu);

    List<EcritureGrandLivre> findByPointageId(Long pointageId);

    /** true si le compte a déjà été mouvementé (interdit alors sa suppression). */
    boolean existsByCompteId(Long compteId);

    /**
     * true si cette pièce porte une opération de trésorerie (caisse, banque
     * ou mobile money) : le journal de l'opération la tient pour réglée,
     * l'extourner seule désynchroniserait le solde du journal de celui du
     * grand livre — voir ComptabiliteService.annuler.
     */
    @Query("""
        select count(e) > 0 from EcritureGrandLivre e
        left join e.piece p
        left join e.transaction t
        left join e.transactionBancaire tb
        left join e.transactionMobileMoney tm
        where p.id = :pieceId
          and (t.id is not null or tb.id is not null or tm.id is not null)
    """)
    boolean existsTresorerieParPieceId(@Param("pieceId") Long pieceId);

    /**
     * Balance agregee (tous comptes) calculee a la volee depuis le Grand Livre.
     * Retourne [numeroCompte, libelle, type, totalDebit, totalCredit].
     */
    @Query("""
        select c.numero, c.libelle, c.type,
               coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        group by c.numero, c.libelle, c.type
        order by c.numero
    """)
    List<Object[]> calculerBalance();

    /**
     * Solde du compte dont le numéro commence par le préfixe donné, en
     * convention des comptes d'ACTIF (trésorerie) : solde = débits - crédits.
     * Un encaissement (débit caisse) augmente donc le solde, un décaissement
     * (crédit caisse) le diminue. Exclut les brouillons.
     */
    @Query("""
        select coalesce(sum(e.debit), 0) - coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where c.numero like :prefixe%
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
    """)
    BigDecimal soldePourCompte(@Param("prefixe") String prefixe);

    /**
     * Lignes du Grand Livre ordonnees par date puis identifiant, avec le compte
     * et la transaction charges (evite les N+1 a l'affichage).
     */
    @Query("""
        select e from EcritureGrandLivre e
        join fetch e.compte
        left join fetch e.transaction
        left join fetch e.piece p
        where (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        order by e.dateEcriture desc, e.id desc
    """)
    List<EcritureGrandLivre> listerAvecDetails();

    /**
     * Lignes du grand livre pour un compte (préfixe) sur une période.
     */
    @Query("""
        select e from EcritureGrandLivre e
        join fetch e.compte c
        left join fetch e.piece p
        left join fetch e.transaction
        where c.numero like :prefixe%
          and e.dateEcriture >= :du
          and e.dateEcriture <= :au
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        order by e.dateEcriture asc, e.id asc
    """)
    List<EcritureGrandLivre> grandLivreParCompte(@Param("prefixe") String prefixe,
                                                 @Param("du") LocalDate du,
                                                 @Param("au") LocalDate au);

    /**
     * Cumul débit/crédit avant la date de début (report à nouveau).
     * Retourne [totalDebit, totalCredit].
     */
    @Query("""
        select coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where c.numero like :prefixe%
          and e.dateEcriture < :du
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
    """)
    Object[] soldeAnterieur(@Param("prefixe") String prefixe, @Param("du") LocalDate du);

    /**
     * Balance de vérification sur une période.
     * Retourne [numeroCompte, libelle, type, totalDebit, totalCredit].
     */
    @Query("""
        select c.numero, c.libelle, c.type,
               coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where e.dateEcriture >= :du and e.dateEcriture <= :au
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        group by c.numero, c.libelle, c.type
        order by c.numero
    """)
    List<Object[]> balanceVerification(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /**
     * Mouvements agrégés par compte sur une période (avec la classe OHADA),
     * pour le compte de résultat.
     *
     * <p>Les pièces marquées « solde d'ouverture » sont exclues : une reprise
     * d'à-nouveaux est datée du premier jour de l'exercice, donc à l'intérieur
     * de la période, mais ne constitue pas un flux de celle-ci — la compter
     * ici transformerait une reprise de charge ou de produit en résultat de
     * l'exercice. Elle est restituée par {@link #soldesOuvertureParCompte}.</p>
     *
     * Retourne [numero, libelle, type, classe, totalDebit, totalCredit].
     */
    @Query("""
        select c.numero, c.libelle, c.type, c.classe,
               coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where e.dateEcriture >= :du and e.dateEcriture <= :au
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
          and (p is null or p.soldeOuverture = false)
        group by c.numero, c.libelle, c.type, c.classe
        order by c.numero
    """)
    List<Object[]> mouvementsParCompte(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /**
     * Cumul par compte jusqu'à une date incluse (avec la classe OHADA), pour le
     * bilan. Retourne [numero, libelle, type, classe, totalDebit, totalCredit].
     */
    @Query("""
        select c.numero, c.libelle, c.type, c.classe,
               coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where e.dateEcriture <= :au
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        group by c.numero, c.libelle, c.type, c.classe
        order by c.numero
    """)
    List<Object[]> cumulParCompte(@Param("au") LocalDate au);

    /**
     * Soldes d'ouverture par compte (avec la classe OHADA). Pendant de
     * {@link #mouvementsParCompte} pour la partie « soldes d'ouverture » d'une
     * balance à 6 colonnes. Deux sources l'alimentent :
     * <ul>
     *   <li>tout ce qui précède la période ({@code dateEcriture < du}) —
     *       l'antériorité comptable ordinaire ;</li>
     *   <li>les pièces explicitement marquées « solde d'ouverture », même
     *       datées dans la période : c'est la reprise des à-nouveaux, saisie
     *       au premier jour de l'exercice.</li>
     * </ul>
     *
     * <p>Les deux branches sont alternatives (OR) sur une même ligne : une
     * reprise datée avant {@code du} n'est donc jamais comptée deux fois.
     * La borne {@code <= au} évite qu'une reprise saisie pour un exercice
     * futur ne remonte dans la période courante.</p>
     *
     * Retourne [numero, libelle, type, classe, totalDebit, totalCredit].
     */
    @Query("""
        select c.numero, c.libelle, c.type, c.classe,
               coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where (e.dateEcriture < :du
               or (p.soldeOuverture = true and e.dateEcriture <= :au))
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        group by c.numero, c.libelle, c.type, c.classe
        order by c.numero
    """)
    List<Object[]> soldesOuvertureParCompte(@Param("du") LocalDate du, @Param("au") LocalDate au);

    /**
     * Journal complet jusqu'à une date, trié chronologiquement : sert de
     * pièce jointe d'audit à l'export Excel des états financiers (feuille
     * « Journal »).
     */
    @Query("""
        select e from EcritureGrandLivre e
        join fetch e.compte
        left join fetch e.piece p
        where e.dateEcriture <= :au
          and (p is null or p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON)
        order by e.dateEcriture asc, e.id asc
    """)
    List<EcritureGrandLivre> journalJusqua(@Param("au") LocalDate au);

    /**
     * Solde net (debit - credit) d'un compte precis sur une piece precise :
     * sert a retrouver, au centime pres, le montant exact deja porte au
     * compte client lors de la vente d'origine plutot que de le recalculer
     * (une reconversion independante pourrait diverger d'un centime par
     * arrondi et laisser un residu sur la creance soldee).
     */
    @Query("""
        select coalesce(sum(e.debit), 0) - coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        where e.piece.id = :pieceId and c.numero = :numero
    """)
    BigDecimal soldePourPieceEtCompte(@Param("pieceId") Long pieceId, @Param("numero") String numero);

    /**
     * Efface les ecritures qui ne sont adossees a aucune transaction de
     * tresorerie : imports precedents, pieces manuelles, soldes d'ouverture.
     * Les ecritures liees a un mouvement reel de caisse, de banque ou de
     * mobile money sont preservees — les supprimer laisserait ces
     * transactions sans contrepartie comptable.
     */
    @Modifying
    @Query("""
        delete from EcritureGrandLivre e
        where e.transaction is null
          and e.transactionBancaire is null
          and e.transactionMobileMoney is null
        """)
    int supprimerEcrituresSansTransaction();

    /**
     * Mouvements mensuels d'une periode par compte des classes budgetaires (2, 6, 7, 8), pour le suivi
     * budgetaire : [numero, type, mois (1-12), debit, credit]. Memes ecritures que le grand livre (pieces
     * non brouillon et operations de tresorerie), sans les a-nouveaux, les reevaluations latentes de change
     * ni la piece de cloture de l'exercice (qui solde les classes 6 et 7 vers le resultat et annulerait le
     * realise de l'annee).
     */
    @Query("""
        select c.numero, c.type, extract(month from e.dateEcriture), coalesce(sum(e.debit), 0), coalesce(sum(e.credit), 0)
        from EcritureGrandLivre e
        join e.compte c
        left join e.piece p
        where e.dateEcriture >= :du and e.dateEcriture <= :au
          and c.classe in (2, 6, 7, 8)
          and (p is null or (p.statut <> com.mbsc.finapp.domain.enums.StatutPiece.BROUILLON
                             and p.soldeOuverture = false
                             and p.reevaluationDevise = false
                             and (p.libelle is null or p.libelle not like 'Clôture de l''exercice%')))
        group by c.numero, c.type, extract(month from e.dateEcriture)
    """)
    List<Object[]> mouvementsMensuelsBudgetaires(@Param("du") LocalDate du, @Param("au") LocalDate au);
}

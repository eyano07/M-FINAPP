package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.MouvementEmballage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MouvementEmballageRepository extends JpaRepository<MouvementEmballage, Long> {

    /**
     * Charge un mouvement a annuler, avec un verrou exclusif tenu jusqu'a la
     * fin de la transaction : deux annulations simultanees du meme mouvement
     * (double clic) liraient sinon toutes deux {@code annule = false} et
     * compenseraient deux fois le compteur de vides. La seconde attend la
     * premiere, puis lit l'etat qu'elle a valide.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MouvementEmballage m where m.id = :id")
    Optional<MouvementEmballage> findByIdPourMiseAJour(Long id);

    /** true si ce mouvement de stock est la sortie d'une perte de boisson (casse, peremption, cadeau). */
    boolean existsByMouvementStockId(Long mouvementStockId);

    /** Mouvements de stock valides qui sont la sortie d'une perte de boisson. */
    @Query("""
        select s.id from MouvementEmballage m join m.mouvementStock s
        where s.statut = com.mbsc.finapp.domain.enums.StatutMouvement.VALIDE
    """)
    List<Long> idsMouvementsStockValides();

    /**
     * Historique de circulation, du plus recent au plus ancien. Les bornes de
     * date et l'emballage sont optionnels : passer {@code null} neutralise le
     * critere correspondant.
     */
    @Query("""
        select m from MouvementEmballage m
        join fetch m.emballage e
        left join fetch m.createdBy
        where (:emballageId is null or e.id = :emballageId)
          and (:du is null or m.dateMouvement >= :du)
          and (:au is null or m.dateMouvement <= :au)
        order by m.dateMouvement desc, m.id desc
    """)
    List<MouvementEmballage> rechercher(Long emballageId, LocalDate du, LocalDate au);

    /**
     * Tous les mouvements d'une periode, tous emballages confondus — alimente
     * le tableau de bord Restaurant. Distincte de {@link #rechercher} plutot
     * que d'y passer un {@code emballageId} null : PostgreSQL ne parvient pas
     * a inferer le type d'un parametre Long litteralement null combine a des
     * dates non nulles dans la meme clause OR (erreur JDBC 42P18, "could not
     * determine data type of parameter"), un ecueil pgjdbc/Hibernate connu sur
     * ce motif de filtre optionnel.
     */
    @Query("""
        select m from MouvementEmballage m
        join fetch m.emballage e
        left join fetch m.createdBy
        where m.dateMouvement between :du and :au
        order by m.dateMouvement desc, m.id desc
    """)
    List<MouvementEmballage> rechercherPeriode(LocalDate du, LocalDate au);
}

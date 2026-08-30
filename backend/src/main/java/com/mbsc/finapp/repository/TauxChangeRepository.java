package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TauxChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TauxChangeRepository extends JpaRepository<TauxChange, Long> {

    /** Historique trié du plus récent au plus ancien. */
    List<TauxChange> findAllByOrderByDateEffetDescCreatedAtDesc();

    /** Taux le plus récent (date effet la plus haute, puis created_at). */
    Optional<TauxChange> findFirstByOrderByDateEffetDescCreatedAtDesc();

    /**
     * Taux en vigueur à une date donnée : le plus récent dont la date d'effet
     * ne lui est pas postérieure.
     *
     * <p>Indispensable pour une opération antidatée : sans cette résolution,
     * une vente ou une pièce enregistrée pour une date passée serait convertie
     * au taux d'aujourd'hui.</p>
     */
    Optional<TauxChange> findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(LocalDate date);

    /**
     * Taux d'une date donnee. Depuis la contrainte d'unicite sur
     * {@code date_effet}, il y en a au plus un : sert a corriger le taux du
     * jour plutot que d'en inserer un second, qui violerait la contrainte.
     */
    Optional<TauxChange> findByDateEffet(LocalDate dateEffet);
}

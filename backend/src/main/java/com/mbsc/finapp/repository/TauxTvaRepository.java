package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TauxTva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TauxTvaRepository extends JpaRepository<TauxTva, Long> {

    /** Historique complet, du plus recent au plus ancien. */
    List<TauxTva> findAllByOrderByDateEffetDescCreatedAtDesc();

    /**
     * Taux en vigueur a une date donnee : le plus recent parmi ceux dont la
     * date d'effet est atteinte. Ignore donc les taux futurs, contrairement
     * a une simple lecture de la derniere ligne.
     */
    Optional<TauxTva> findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(LocalDate date);
}

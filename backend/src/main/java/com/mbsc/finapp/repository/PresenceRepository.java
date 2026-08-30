package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Presence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PresenceRepository extends JpaRepository<Presence, Long> {

    Optional<Presence> findByEmployeIdAndDate(Long employeId, LocalDate date);

    @Query("SELECT p FROM Presence p WHERE p.date BETWEEN :debut AND :fin")
    List<Presence> findByDateBetween(LocalDate debut, LocalDate fin);

    @Query("SELECT p FROM Presence p WHERE p.employe.id = :employeId AND p.date BETWEEN :debut AND :fin")
    List<Presence> findByEmployeAndDateBetween(Long employeId, LocalDate debut, LocalDate fin);
}

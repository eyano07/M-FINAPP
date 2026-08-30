package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TransactionBancaire;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionBancaireRepository extends JpaRepository<TransactionBancaire, Long> {
    Optional<TransactionBancaire> findByUuid(UUID uuid);
    boolean existsByUuid(UUID uuid);
    boolean existsByReference(String reference);
    List<TransactionBancaire> findAllByOrderByDateEnregistrementDesc();
}

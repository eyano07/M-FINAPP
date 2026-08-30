package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TransactionCaisse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionCaisseRepository extends JpaRepository<TransactionCaisse, Long> {
    Optional<TransactionCaisse> findByUuid(UUID uuid);
    boolean existsByUuid(UUID uuid);
    boolean existsByReference(String reference);
    List<TransactionCaisse> findAllByOrderByDateEnregistrementDesc();
}

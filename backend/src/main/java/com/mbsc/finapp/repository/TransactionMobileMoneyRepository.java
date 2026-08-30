package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.TransactionMobileMoney;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionMobileMoneyRepository extends JpaRepository<TransactionMobileMoney, Long> {
    Optional<TransactionMobileMoney> findByUuid(UUID uuid);
    boolean existsByUuid(UUID uuid);
    boolean existsByReference(String reference);
    List<TransactionMobileMoney> findAllByOrderByDateEnregistrementDesc();
}

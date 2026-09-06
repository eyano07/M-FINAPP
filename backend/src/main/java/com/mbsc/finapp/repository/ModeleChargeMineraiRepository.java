package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ModeleChargeMinerai;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModeleChargeMineraiRepository extends JpaRepository<ModeleChargeMinerai, Long> {

    List<ModeleChargeMinerai> findByArticleIdOrderByLibelleAsc(Long articleId);

    Optional<ModeleChargeMinerai> findByArticleIdAndLibelleIgnoreCase(Long articleId, String libelle);
}

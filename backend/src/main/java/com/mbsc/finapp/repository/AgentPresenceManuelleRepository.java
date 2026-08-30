package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.AgentPresenceManuelle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPresenceManuelleRepository extends JpaRepository<AgentPresenceManuelle, Long> {
    List<AgentPresenceManuelle> findAllByOrderByOrdreAffichageAscNomCompletAsc();
}

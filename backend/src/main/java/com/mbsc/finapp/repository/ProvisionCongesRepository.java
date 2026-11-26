package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ProvisionConges;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProvisionCongesRepository extends JpaRepository<ProvisionConges, Long> {

    Optional<ProvisionConges> findFirstByAnneeAndStatut(Integer annee, String statut);

    List<ProvisionConges> findAllByOrderByAnneeDescIdDesc();
}

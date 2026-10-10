package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.FactureNormalisee;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FactureNormaliseeRepository extends JpaRepository<FactureNormalisee, Long> {

    List<FactureNormalisee> findByVenteIdOrderByIdAsc(Long venteId);

    Optional<FactureNormalisee> findByVenteIdAndType(Long venteId, FactureNormalisee.Type type);

    @Query("select f from FactureNormalisee f where f.statut = com.mbsc.finapp.domain.FactureNormalisee$Statut.EN_ATTENTE "
        + "and (f.prochaineTentative is null or f.prochaineTentative <= :maintenant) order by f.id")
    List<FactureNormalisee> aRetransmettre(Instant maintenant, Pageable page);

    @Query("select f from FactureNormalisee f join fetch f.vente v where f.statut in "
        + "(com.mbsc.finapp.domain.FactureNormalisee$Statut.EN_ATTENTE, com.mbsc.finapp.domain.FactureNormalisee$Statut.REJETEE) "
        + "order by f.id desc")
    List<FactureNormalisee> aRegulariser();

    long countByStatut(FactureNormalisee.Statut statut);
}

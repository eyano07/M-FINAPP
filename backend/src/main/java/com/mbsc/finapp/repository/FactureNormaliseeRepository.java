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

    /** Facture de la vente ou avoir de son annulation complète (uniques par vente) : jamais un avoir partiel de retour. */
    Optional<FactureNormalisee> findByVenteIdAndTypeAndRetourIsNull(Long venteId, FactureNormalisee.Type type);

    /** Avoir partiel d'un retour remboursé (V134). */
    Optional<FactureNormalisee> findByRetourId(Long retourId);

    @Query("select f from FactureNormalisee f where f.statut = com.mbsc.finapp.domain.FactureNormalisee$Statut.EN_ATTENTE "
        + "and (f.prochaineTentative is null or f.prochaineTentative <= :maintenant) order by f.id")
    List<FactureNormalisee> aRetransmettre(Instant maintenant, Pageable page);

    @Query("select f from FactureNormalisee f join fetch f.vente v where f.statut in "
        + "(com.mbsc.finapp.domain.FactureNormalisee$Statut.EN_ATTENTE, com.mbsc.finapp.domain.FactureNormalisee$Statut.REJETEE) "
        + "order by f.id desc")
    List<FactureNormalisee> aRegulariser();

    long countByStatut(FactureNormalisee.Statut statut);
}

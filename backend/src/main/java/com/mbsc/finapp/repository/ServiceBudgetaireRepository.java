package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ServiceBudgetaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ServiceBudgetaireRepository extends JpaRepository<ServiceBudgetaire, Long> {

    /** Services actifs d'abord, puis par nom. */
    List<ServiceBudgetaire> findAllByOrderByActifDescNomAsc();

    boolean existsByNomIgnoreCase(String nom);

    boolean existsByNomIgnoreCaseAndIdNot(String nom, Long id);

    /** Nombre de lignes de budget par service : [serviceId, nombre]. */
    @Query("select l.service.id, count(l) from LigneBudget l where l.service is not null group by l.service.id")
    List<Object[]> compterLignesParService();

    /** Nombre de notes de frais par service : [serviceId, nombre]. */
    @Query("select n.service.id, count(n) from NoteFrais n where n.service is not null group by n.service.id")
    List<Object[]> compterNotesParService();
}

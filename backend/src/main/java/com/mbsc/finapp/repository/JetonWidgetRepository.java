package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.JetonWidget;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface JetonWidgetRepository extends JpaRepository<JetonWidget, Long> {

    @EntityGraph(attributePaths = "utilisateur")
    Optional<JetonWidget> findByEmpreinte(String empreinte);

    /** Comptes dont un téléphone a les widgets (jeton remis à la connexion dans l'application). */
    @Query("select distinct j.utilisateur.id from JetonWidget j")
    java.util.List<Long> comptesAvecWidget();

    @Transactional
    @Modifying
    @Query("delete from JetonWidget j where j.empreinte = :empreinte")
    int supprimerEmpreinte(@Param("empreinte") String empreinte);
}

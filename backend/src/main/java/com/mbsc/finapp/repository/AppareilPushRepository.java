package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.AppareilPush;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AppareilPushRepository extends JpaRepository<AppareilPush, Long> {

    Optional<AppareilPush> findByJeton(String jeton);

    @Query("SELECT a.jeton FROM AppareilPush a WHERE a.utilisateur.id = :utilisateurId")
    List<String> jetonsDe(@Param("utilisateurId") Long utilisateurId);

    @Transactional
    @Modifying
    @Query("DELETE FROM AppareilPush a WHERE a.jeton = :jeton")
    int supprimerJeton(@Param("jeton") String jeton);

    @Transactional
    @Modifying
    @Query("DELETE FROM AppareilPush a WHERE a.jeton = :jeton AND a.utilisateur.id = :utilisateurId")
    int supprimerJetonDe(@Param("jeton") String jeton, @Param("utilisateurId") Long utilisateurId);
}

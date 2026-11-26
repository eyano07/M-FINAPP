package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.PreferenceUtilisateur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PreferenceUtilisateurRepository extends JpaRepository<PreferenceUtilisateur, Long> {

    List<PreferenceUtilisateur> findByUserId(Long userId);

    /** Comptes qui ont choisi cette valeur (ex. widget.tresorerie = ACTIF). */
    List<PreferenceUtilisateur> findByCleAndValeur(String cle, String valeur);

    Optional<PreferenceUtilisateur> findByUserIdAndCle(Long userId, String cle);
}

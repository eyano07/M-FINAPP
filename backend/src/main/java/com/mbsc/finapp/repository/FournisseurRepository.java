package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Fournisseur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FournisseurRepository extends JpaRepository<Fournisseur, Long> {

    List<Fournisseur> findAllByOrderByNomAsc();

    @Query("select count(f) > 0 from Fournisseur f where lower(trim(f.nom)) = lower(trim(:nom)) and (:id is null or f.id <> :id)")
    boolean nomDejaPris(@Param("nom") String nom, @Param("id") Long id);

    /** Lots de stock (achats du restaurant et de la logistique) dont le fournisseur porte ce nom. */
    @Query("select count(l) from LotStock l where lower(trim(l.fournisseur)) = lower(trim(:nom))")
    long compterLots(@Param("nom") String nom);

    /** Immobilisations achetées à ce fournisseur. */
    @Query("select count(i) from Immobilisation i where lower(trim(i.fournisseur)) = lower(trim(:nom))")
    long compterImmobilisations(@Param("nom") String nom);
}

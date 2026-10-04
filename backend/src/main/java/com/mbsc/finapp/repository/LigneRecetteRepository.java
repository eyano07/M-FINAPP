package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.LigneRecette;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LigneRecetteRepository extends JpaRepository<LigneRecette, Long> {

    /** La provision est toujours affichee avec sa ligne : jointure anticipee pour eviter le N+1. */
    @Query("""
        select l from LigneRecette l
        join fetch l.provision
        where l.plat.id = :platId
        order by l.id
    """)
    List<LigneRecette> findByPlatIdAvecProvision(Long platId);

    /** Toutes les fiches d'un coup, pour l'ecran de synthese. */
    @Query("""
        select l from LigneRecette l
        join fetch l.provision
        join fetch l.plat
        order by l.plat.code, l.id
    """)
    List<LigneRecette> findAllAvecArticles();

    void deleteByPlatId(Long platId);

    boolean existsByProvisionId(Long provisionId);

    boolean existsByPlatId(Long platId);
}

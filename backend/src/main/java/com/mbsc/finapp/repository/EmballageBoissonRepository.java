package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.EmballageBoisson;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmballageBoissonRepository extends JpaRepository<EmballageBoisson, Long> {

    Optional<EmballageBoisson> findByCode(String code);

    boolean existsByCode(String code);

    /** Une boisson n'a qu'un seul conditionnement (contrainte d'unicite en base). */
    Optional<EmballageBoisson> findByArticleBoissonId(Long articleId);

    boolean existsByArticleBoissonId(Long articleId);

    /** La boisson est affichee dans la liste : jointure anticipee pour eviter le N+1. */
    @Query("""
        select e from EmballageBoisson e
        join fetch e.articleBoisson
        order by e.code
    """)
    List<EmballageBoisson> findAllAvecBoisson();

    // ── Chargements verrouilles, pour toute modification du compteur ──────
    //
    // Le compteur de vides se met a jour en lire-modifier-ecrire. Sans verrou,
    // deux ventes simultanees de la meme boisson lisaient la meme valeur et
    // ecrivaient le meme resultat : un des deux mouvements etait perdu, alors
    // que le journal enregistrait bien les deux. L'invariant du module
    // (somme signee du journal = compteur) s'en trouvait rompu, sans erreur ni
    // trace. Un verrou exclusif, tenu jusqu'au commit, serialise ces mises a
    // jour ; les transactions concernees ne font que quelques ecritures, la
    // contention reste negligeable.
    //
    // A utiliser sur TOUT chemin qui appelle appliquerEtJournaliser.

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmballageBoisson e where e.id = :id")
    Optional<EmballageBoisson> findByIdPourMiseAJour(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmballageBoisson e where e.articleBoisson.id = :articleId")
    Optional<EmballageBoisson> findByArticleBoissonIdPourMiseAJour(Long articleId);
}

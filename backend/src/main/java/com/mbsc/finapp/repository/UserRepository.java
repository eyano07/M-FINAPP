package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    /**
     * Comptes dont l'identifiant de connexion ne differe que par la casse. Une liste, pas un
     * {@code Optional} : la colonne n'est unique qu'a la casse pres, et d'anciens comptes peuvent
     * avoir ete enregistres avec des majuscules (voir {@code AppUserDetailsService}).
     */
    @EntityGraph(attributePaths = "roles")
    List<User> findAllByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "roles")
    List<User> findAllByOrderByNomAscPrenomAsc();

    /** Destinataires d'une notification adressee a un role (comptes actifs uniquement). */
    List<User> findByRoles_NomAndActifTrue(RoleType nom);
}

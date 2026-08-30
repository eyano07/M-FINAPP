package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByNom(RoleType nom);
}

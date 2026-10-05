package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.RoleLibelle;
import com.mbsc.finapp.domain.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleLibelleRepository extends JpaRepository<RoleLibelle, RoleType> {
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.RolePermission;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRoleIn(List<RoleType> roles);

    Optional<RolePermission> findByRoleAndModule(RoleType role, ModuleMetier module);
}

package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleConfigRepository extends JpaRepository<ModuleConfig, ModuleMetier> {
}

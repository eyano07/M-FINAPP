package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.dto.parametrage.ModuleConfigResponse;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Activation/desactivation globale des modules metier par l'administrateur. */
@Service
@RequiredArgsConstructor
public class ModuleConfigService {

    private final ModuleConfigRepository repository;

    @Transactional(readOnly = true)
    public java.util.List<ModuleConfigResponse> lister() {
        Map<ModuleMetier, ModuleConfig> existants = repository.findAll().stream()
            .collect(Collectors.toMap(ModuleConfig::getModule, mc -> mc));
        return Arrays.stream(ModuleMetier.values())
            .map(m -> existants.containsKey(m)
                ? ModuleConfigResponse.from(existants.get(m))
                : new ModuleConfigResponse(m, true, null))
            .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ModuleConfigResponse definir(ModuleMetier module, boolean actif) {
        ModuleConfig mc = repository.findById(module)
            .orElseGet(() -> ModuleConfig.builder().module(module).build());
        mc.setActif(actif);
        mc = repository.save(mc);
        return ModuleConfigResponse.from(mc);
    }
}

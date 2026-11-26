package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Les roles qu'on peut encore attribuer : ceux qui ne sont lies a aucun module (direction, comptable,
 * administrateur) ou dont au moins un module est actif — voir {@link ModulesDesRoles}. Un role dont le module est
 * desactive disparait de la gestion des utilisateurs ; les comptes qui n'ont plus que de tels roles sont
 * desactives et masques ({@link SynchronisationComptesModules}).
 */
@Service
@RequiredArgsConstructor
public class RolesDisponibles {

    private final ModuleConfigRepository modules;

    /** L'etat actuel des modules : une seule lecture, a garder pour toute la suite d'une operation. */
    @Transactional(readOnly = true)
    public EtatModules etat() {
        return EtatModules.de(modules.findAll());
    }

    /** Roles attribuables aujourd'hui, dans l'ordre de l'enumeration. */
    @Transactional(readOnly = true)
    public List<RoleType> disponibles() {
        EtatModules etat = etat();
        return Arrays.stream(RoleType.values())
            .filter(role -> ModulesDesRoles.estDisponible(role, etat::estActif))
            .toList();
    }
}

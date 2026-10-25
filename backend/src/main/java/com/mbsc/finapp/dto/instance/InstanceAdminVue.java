package com.mbsc.finapp.dto.instance;

import com.mbsc.finapp.service.instance.ActivationModeLocalService;

/**
 * Ecran Administration › Serveur local et synchronisation : etat de l'instance, avancement de l'activation du mode local
 * et preparation de la base (declencheurs, cles etrangeres differables).
 */
public record InstanceAdminVue(
    InstanceAdminResponse etat,
    ActivationModeLocalService.Progression activation,
    boolean schemaPret,
    String schemaProbleme
) {}

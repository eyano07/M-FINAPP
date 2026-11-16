package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;

/**
 * Etat d'un module. Apres une bascule, {@code comptesDesactives} et {@code comptesReactives} disent combien de
 * comptes la cascade des modules a coupes ou remis en service (voir SynchronisationComptesModules) ; ils valent
 * {@code null} dans la liste des modules, ou rien n'a change.
 */
public record ModuleConfigResponse(ModuleMetier module, boolean actif, ModuleMetier parentModule,
                                   Integer comptesDesactives, Integer comptesReactives) {

    /** Etat sans compteurs (liste des modules). */
    public ModuleConfigResponse(ModuleMetier module, boolean actif, ModuleMetier parentModule) {
        this(module, actif, parentModule, null, null);
    }

    public static ModuleConfigResponse from(ModuleConfig m) {
        return new ModuleConfigResponse(m.getModule(), m.isActif(), m.getParentModule());
    }

    /** Etat d'un module qui vient d'etre bascule, avec le nombre de comptes touches par la cascade. */
    public static ModuleConfigResponse from(ModuleConfig m, int comptesDesactives, int comptesReactives) {
        return new ModuleConfigResponse(m.getModule(), m.isActif(), m.getParentModule(), comptesDesactives, comptesReactives);
    }
}

package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;

public record ModuleConfigResponse(ModuleMetier module, boolean actif, ModuleMetier parentModule) {
    public static ModuleConfigResponse from(ModuleConfig m) {
        return new ModuleConfigResponse(m.getModule(), m.isActif(), m.getParentModule());
    }
}

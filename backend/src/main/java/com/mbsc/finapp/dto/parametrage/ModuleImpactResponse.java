package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;

import java.util.List;

/**
 * Ce que changerait la bascule d'un module, sans rien ecrire : les roles dont la disponibilite change, et les
 * comptes que la cascade desactiverait (ou reactiverait). Alimente la confirmation avant de couper un module.
 */
public record ModuleImpactResponse(
    ModuleMetier module,
    boolean actif,
    List<RoleType> roles,
    List<String> comptesADesactiver,
    List<String> comptesAReactiver
) {
}

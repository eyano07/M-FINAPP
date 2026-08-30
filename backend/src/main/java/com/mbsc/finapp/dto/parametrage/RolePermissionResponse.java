package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;

/** Une cellule de la grille role x module, y compris quand aucune ligne n'existe encore (niveau AUCUN). */
public record RolePermissionResponse(RoleType role, ModuleMetier module, NiveauPermission niveau) {}

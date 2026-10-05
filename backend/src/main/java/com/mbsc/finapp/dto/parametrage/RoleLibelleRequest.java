package com.mbsc.finapp.dto.parametrage;

import jakarta.validation.constraints.Size;

/** Libelle vide ou absent = retour au libelle par defaut. */
public record RoleLibelleRequest(@Size(max = 60) String libelle) {}

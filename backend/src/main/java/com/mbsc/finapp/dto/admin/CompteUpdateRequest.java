package com.mbsc.finapp.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la requête PUT /comptes/{id} — renomme un compte ajouté
 * manuellement. Seul le libellé est modifiable : le numéro, le type et la
 * classe sont hérités du parent à la création et ne changent pas ensuite
 * (les faire varier renumérote le compte et déstabiliserait les éventuelles
 * écritures déjà passées).
 */
public record CompteUpdateRequest(
    @NotBlank @Size(max = 200) String libelle
) {}

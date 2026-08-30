package com.mbsc.finapp.dto.comptabilite;

/** Corps de {@code PATCH /comptabilite/pieces/{id}/solde-ouverture} — voir {@code ComptabiliteService.basculerSoldeOuverture}. */
public record SoldeOuvertureRequest(boolean soldeOuverture) {}

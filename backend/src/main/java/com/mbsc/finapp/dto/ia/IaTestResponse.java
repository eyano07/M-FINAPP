package com.mbsc.finapp.dto.ia;

/** Résultat d'un test de clé et de modèle : un message clair, jamais le détail brut de l'erreur du fournisseur. */
public record IaTestResponse(boolean ok, String message) {}

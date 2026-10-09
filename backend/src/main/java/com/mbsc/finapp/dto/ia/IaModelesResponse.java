package com.mbsc.finapp.dto.ia;

import java.util.List;

/** Modèles proposés par le fournisseur pour la clé enregistrée, et modèle actuellement retenu. */
public record IaModelesResponse(String modeleActuel, String modeleParDefaut, List<String> modeles) {}

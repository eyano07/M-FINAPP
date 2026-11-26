package com.mbsc.finapp.dto.ia;

import jakarta.validation.constraints.Size;

/** Nouvelle clé et/ou nouveau modèle d'un fournisseur ; un champ absent reste inchangé. */
public record IaEnregistrerRequest(@Size(max = 400) String cle, @Size(max = 100) String modele) {}

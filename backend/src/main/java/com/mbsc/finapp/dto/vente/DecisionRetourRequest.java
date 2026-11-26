package com.mbsc.finapp.dto.vente;

import jakarta.validation.constraints.Size;

/** Accord ou refus d'un retour de vente par le responsable : le commentaire est obligatoire pour un refus. */
public record DecisionRetourRequest(@Size(max = 500) String commentaire) {}

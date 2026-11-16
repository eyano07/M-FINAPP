package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.Size;

/** Commentaire d'une décision (approbation ou refus) sur une demande de clôture, ou motif d'un paramètre. */
public record DecisionRequest(@Size(max = 1000) String commentaire) {
}

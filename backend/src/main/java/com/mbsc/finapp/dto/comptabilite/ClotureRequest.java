package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Nouvelle date du verrou de période ({@code null} = lever le verrou) et motif. Avancer le verrou est immédiat ; le
 * reculer ou le lever crée une demande que le DA ou le DG doit approuver, motif obligatoire.
 */
public record ClotureRequest(LocalDate dateCloture, @Size(max = 1000) String motif) {
}

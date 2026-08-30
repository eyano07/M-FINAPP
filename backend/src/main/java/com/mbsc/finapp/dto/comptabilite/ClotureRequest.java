package com.mbsc.finapp.dto.comptabilite;

import java.time.LocalDate;

/** Définition de la date de clôture comptable ({@code null} = aucune clôture). */
public record ClotureRequest(LocalDate dateCloture) {
}

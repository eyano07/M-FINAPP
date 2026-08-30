package com.mbsc.finapp.dto.comptabilite;

import java.time.LocalDate;

/** État de la clôture comptable. */
public record ClotureResponse(LocalDate dateCloture) {
}

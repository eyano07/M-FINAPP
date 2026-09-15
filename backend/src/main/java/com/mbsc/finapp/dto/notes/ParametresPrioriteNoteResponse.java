package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.ParametresPrioriteNote;

import java.math.BigDecimal;
import java.time.Instant;

public record ParametresPrioriteNoteResponse(
    BigDecimal seuilBasse,
    BigDecimal seuilMoyenne,
    BigDecimal seuilHaute,
    /** Nom complet du dernier DA ayant enregistre ces seuils, ou null. */
    String majParNom,
    Instant majLe
) {
    public static ParametresPrioriteNoteResponse from(ParametresPrioriteNote p) {
        String nom = null;
        if (p.getMajPar() != null) {
            nom = ((p.getMajPar().getPrenom() == null ? "" : p.getMajPar().getPrenom()) + " "
                + (p.getMajPar().getNom() == null ? "" : p.getMajPar().getNom())).trim();
            if (nom.isEmpty()) nom = p.getMajPar().getEmail();
        }
        return new ParametresPrioriteNoteResponse(
            p.getSeuilBasse(), p.getSeuilMoyenne(), p.getSeuilHaute(), nom, p.getMajLe());
    }
}

package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.SortieTravailleur;

import java.time.LocalDate;
import java.time.LocalTime;

public record SortieTravailleurResponse(
    Long id, Long employeId, String employeNomComplet, LocalDate dateSortie, LocalTime heureSortie,
    LocalTime heureRetour, String motif, String notes, Long dureeMinutes
) {
    public static SortieTravailleurResponse from(SortieTravailleur s) {
        return new SortieTravailleurResponse(
            s.getId(), s.getEmploye().getId(), s.getEmploye().getNomComplet(), s.getDateSortie(),
            s.getHeureSortie(), s.getHeureRetour(), s.getMotif(), s.getNotes(), s.getDureeMinutes());
    }
}

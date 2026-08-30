package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.RotationSuperviseur;

import java.time.LocalDate;

public record RotationSuperviseurResponse(
    Long id, Long employeId, String employeNomComplet, Long siteId, String siteNom,
    Integer annee, Integer mois, Integer numeroCycle,
    LocalDate prestationDebut, LocalDate prestationFin, Integer joursPrestation,
    LocalDate reposDebut, LocalDate reposFin, Integer joursRepos, String notes
) {
    public static RotationSuperviseurResponse from(RotationSuperviseur r) {
        return new RotationSuperviseurResponse(
            r.getId(), r.getEmploye().getId(), r.getEmploye().getNomComplet(),
            r.getSite().getId(), r.getSite().getNom(),
            r.getAnnee(), r.getMois(), r.getNumeroCycle(),
            r.getPrestationDebut(), r.getPrestationFin(),
            RotationSuperviseur.joursInclusifs(r.getPrestationDebut(), r.getPrestationFin()),
            r.getReposDebut(), r.getReposFin(),
            RotationSuperviseur.joursInclusifs(r.getReposDebut(), r.getReposFin()),
            r.getNotes());
    }
}

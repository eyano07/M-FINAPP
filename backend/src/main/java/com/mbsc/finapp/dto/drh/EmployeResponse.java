package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.enums.SituationFamiliale;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EmployeResponse(
    Long id,
    String matricule,
    String nomComplet,
    String categorie,
    String poste,
    String affectation,
    String email,
    String telephone,
    LocalDate dateEmbauche,
    BigDecimal salaireBaseUsd,
    SituationFamiliale situationFamiliale,
    Integer nombreEnfants,
    String diplome,
    Integer ancienneteAnnees,
    BigDecimal rendementPct,
    boolean conforme,
    boolean superviseur,
    boolean expatrie,
    boolean actif
) {
    public static EmployeResponse from(Employe e) {
        return new EmployeResponse(
            e.getId(), e.getMatricule(), e.getNomComplet(), e.getCategorie(), e.getPoste(), e.getAffectation(),
            e.getEmail(), e.getTelephone(), e.getDateEmbauche(), e.getSalaireBaseUsd(),
            e.getSituationFamiliale(), e.getNombreEnfants(), e.getDiplome(), e.getAncienneteAnnees(),
            e.getRendementPct(), e.isConforme(), e.isSuperviseur(), e.isExpatrie(), e.isActif());
    }
}

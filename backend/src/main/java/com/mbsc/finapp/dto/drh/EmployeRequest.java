package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.enums.SituationFamiliale;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Création et modification partagent le même contrat : le matricule n'y
 * figure pas, il est toujours auto-généré côté serveur (voir
 * {@code EmployeService#genererMatricule}), jamais saisi.
 */
public record EmployeRequest(
    @NotBlank @Size(max = 150) String nomComplet,
    @Size(max = 30) String categorie,
    @Size(max = 100) String poste,
    @Size(max = 100) String affectation,
    @Email @Size(max = 150) String email,
    @Size(max = 30) String telephone,
    LocalDate dateEmbauche,
    @Past LocalDate dateNaissance,
    @NotNull @PositiveOrZero BigDecimal salaireBaseUsd,
    SituationFamiliale situationFamiliale,
    @PositiveOrZero Integer nombreEnfants,
    @Size(max = 100) String diplome,
    @PositiveOrZero Integer ancienneteAnnees,
    @PositiveOrZero BigDecimal rendementPct,
    boolean conforme,
    boolean superviseur,
    boolean expatrie
) {}

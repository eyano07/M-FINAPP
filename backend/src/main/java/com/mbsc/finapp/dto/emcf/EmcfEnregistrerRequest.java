package com.mbsc.finapp.dto.emcf;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** PUT /admin/facturation-normalisee. {@code jeton} absent ou vide = inchange ; {@code groupes} absent = inchanges. */
public record EmcfEnregistrerRequest(
    boolean actif,
    @Pattern(regexp = "SIMULATION|TEST|PRODUCTION", message = "Mode invalide") String mode,
    @Size(max = 300) String urlBase,
    @Size(max = 500) String jeton,
    @Size(max = 60) String numeroDef,
    @Min(1000) @Max(60000) Integer delaiMs,
    @Valid List<Groupe> groupes
) {
    public record Groupe(
        @NotBlank @Pattern(regexp = "[A-Z]{1,3}", message = "Code de groupe invalide (lettres majuscules)") String code,
        @NotBlank @Size(max = 120) String libelle,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taux,
        boolean actif
    ) {}
}

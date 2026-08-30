package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record LigneEcritureRequest(
    @NotBlank String compteNumero,
    @PositiveOrZero BigDecimal debit,
    @PositiveOrZero BigDecimal credit,
    @Size(max = 255) String libelle
) {}

package com.mbsc.finapp.dto.ia;

import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param sens optionnel : DECAISSEMENT (defaut) suggere un compte de charge,
 *             ENCAISSEMENT suggere un compte de produit/passif.
 */
public record SuggestionCompteRequest(
    @NotBlank
    @Size(max = 500)
    String description,
    SensTransaction sens
) {}

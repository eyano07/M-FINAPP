package com.mbsc.finapp.dto.notes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Corps de POST /notes-frais/{id}/retours-fonds : l'emetteur d'une note payee la reduit. Pour chaque ligne citee, la
 * nouvelle quantite (achat de marchandise : bouteilles, kg, litres...) ou le nouveau montant HT (toute autre ligne).
 */
public record RetourFondsRequest(
    @NotBlank @Size(max = 500) String motif,
    @NotEmpty @Valid List<Ligne> lignes
) {
    public record Ligne(
        @NotNull Long ligneId,
        @PositiveOrZero BigDecimal nouvelleQuantite,
        @PositiveOrZero BigDecimal nouveauMontant
    ) {}
}

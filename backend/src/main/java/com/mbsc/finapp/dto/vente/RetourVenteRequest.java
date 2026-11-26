package com.mbsc.finapp.dto.vente;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demande de retour d'une vente payee (caissier) : lignes et quantites rendues, motif.
 *
 * @param lignes au moins une ligne ; une ligne de vente n'y figure qu'une fois
 */
public record RetourVenteRequest(
    @NotBlank @Size(max = 500) String motif,
    @NotEmpty List<@Valid Ligne> lignes
) {
    /**
     * @param remiseEnStock marchandise intacte remise en stock ; absente : oui pour une boisson, non pour un plat (servi)
     */
    public record Ligne(@NotNull Long ligneVenteId, @NotNull @Positive BigDecimal quantite, Boolean remiseEnStock) {}
}

package com.mbsc.finapp.dto.caisse;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Reglement en especes de la dette fournisseur nee de la reception de camions
 * de minerais — voir {@code CaisseService.reglerCamionsMinerai}. Le montant
 * n'est pas saisi : il est la somme des prix d'achat des camions et des frais
 * accessoires retenus, la dette etant deja constatee a la reception.
 *
 * @param camionIds camions dont on solde le prix d'achat
 * @param chargeIds frais accessoires (transport, pont bascule, peage...) dont
 *                  on solde la dette. Independants des camions : un chargement
 *                  peut etre paye au fournisseur sans que le transporteur le
 *                  soit encore, et inversement.
 */
public record ReglementCamionsRequest(
    List<Long> camionIds,
    List<Long> chargeIds,
    @Size(max = 255) String libelle
) {
    public List<Long> camionIds() { return camionIds == null ? List.of() : camionIds; }
    public List<Long> chargeIds() { return chargeIds == null ? List.of() : chargeIds; }
}

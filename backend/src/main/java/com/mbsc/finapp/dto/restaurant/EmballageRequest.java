package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Conditionnement d'une boisson.
 *
 * <p>Le stock de bouteilles vides ne figure PAS ici : il ne se saisit jamais
 * directement, il resulte des mouvements (ventes, achats, casse). C'est ce qui
 * garantit que l'historique explique toujours l'etat courant.</p>
 *
 * @param contenanceCasier  nombre de bouteilles par casier (24 pour le Coca 33 cl)
 */
public record EmballageRequest(
    @NotBlank String code,
    @NotBlank String libelle,
    @NotNull Long articleBoissonId,
    @NotNull @Positive Integer contenanceCasier,
    String format,
    Boolean actif
) {}

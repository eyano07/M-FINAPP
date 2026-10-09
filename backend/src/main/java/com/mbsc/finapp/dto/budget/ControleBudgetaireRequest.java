package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lignes d'une note de frais en cours de saisie, a confronter au budget en execution avant l'enregistrement
 * (l'ecran affiche le resultat ligne par ligne et demande une justification si besoin).
 */
public record ControleBudgetaireRequest(
    Devise devise,
    @NotNull @Valid List<Depense> lignes,
    /** Note deja enregistree que l'on modifie : ses propres engagements ne sont pas comptes deux fois. */
    Long noteId
) {
    public record Depense(
        String compteNumero,
        BigDecimal montant,
        BigDecimal quantite,
        Boolean achatMarchandise
    ) {}
}

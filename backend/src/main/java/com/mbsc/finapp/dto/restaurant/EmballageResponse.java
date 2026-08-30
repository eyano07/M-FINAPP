package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.EmballageBoisson;

/**
 * @param bouteillesVides    stock total de bouteilles vides — la seule donnee stockee
 * @param casiers            casiers complets que cela represente (derive)
 * @param bouteillesRestantes bouteilles au-dela des casiers complets (derive)
 */
public record EmballageResponse(
    Long id,
    String code,
    String libelle,
    String format,
    Long articleBoissonId,
    String articleBoissonCode,
    String articleBoissonLibelle,
    Integer contenanceCasier,
    Integer bouteillesVides,
    Integer casiers,
    Integer bouteillesRestantes,
    boolean actif
) {
    public static EmballageResponse from(EmballageBoisson e) {
        var boisson = e.getArticleBoisson();
        return new EmballageResponse(
            e.getId(),
            e.getCode(),
            e.getLibelle(),
            e.getFormat(),
            boisson == null ? null : boisson.getId(),
            boisson == null ? null : boisson.getCode(),
            boisson == null ? null : boisson.getLibelle(),
            e.getContenanceCasier(),
            e.getBouteillesVides(),
            e.casiers(),
            e.bouteillesRestantes(),
            e.isActif()
        );
    }
}

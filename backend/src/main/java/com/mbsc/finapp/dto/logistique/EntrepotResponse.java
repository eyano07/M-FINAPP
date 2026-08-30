package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.Entrepot;

public record EntrepotResponse(
    Long id,
    String code,
    String nom,
    String localisation,
    boolean actif
) {
    public static EntrepotResponse from(Entrepot e) {
        return new EntrepotResponse(e.getId(), e.getCode(), e.getNom(), e.getLocalisation(), e.isActif());
    }
}

package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.Client;

public record ClientResponse(
    Long id,
    String code,
    String nom,
    String telephone,
    String email,
    String adresse,
    boolean actif
) {
    public static ClientResponse from(Client c) {
        return new ClientResponse(
            c.getId(), c.getCode(), c.getNom(),
            c.getTelephone(), c.getEmail(), c.getAdresse(), c.isActif());
    }
}

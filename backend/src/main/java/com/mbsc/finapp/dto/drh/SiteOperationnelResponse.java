package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.SiteOperationnel;

public record SiteOperationnelResponse(Long id, String nom, String localisation, boolean actif) {
    public static SiteOperationnelResponse from(SiteOperationnel s) {
        return new SiteOperationnelResponse(s.getId(), s.getNom(), s.getLocalisation(), s.isActif());
    }
}

package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.AgentPresenceManuelle;

public record AgentPresenceManuelleResponse(Long id, String nomComplet, String fonction, Integer ordreAffichage) {
    public static AgentPresenceManuelleResponse from(AgentPresenceManuelle a) {
        return new AgentPresenceManuelleResponse(a.getId(), a.getNomComplet(), a.getFonction(), a.getOrdreAffichage());
    }
}

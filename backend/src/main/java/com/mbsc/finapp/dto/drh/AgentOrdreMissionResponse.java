package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.AgentOrdreMission;

public record AgentOrdreMissionResponse(
    Long id, Long employeId, String employeNomComplet, String fonctionMission, String civilite
) {
    public static AgentOrdreMissionResponse from(AgentOrdreMission a) {
        return new AgentOrdreMissionResponse(
            a.getId(), a.getEmploye().getId(), a.getEmploye().getNomComplet(), a.getFonctionMission(),
            a.getCivilite());
    }
}

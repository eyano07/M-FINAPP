package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.AgentOrdreMission;

public record AgentOrdreMissionResponse(
    Long id, Long employeId, String employeNomComplet, String nomLibre, String nomAffiche,
    String nationalite, String numeroPasseport, String fonctionMission, String civilite
) {
    public static AgentOrdreMissionResponse from(AgentOrdreMission a) {
        return new AgentOrdreMissionResponse(
            a.getId(), a.getEmploye() != null ? a.getEmploye().getId() : null,
            a.getEmploye() != null ? a.getEmploye().getNomComplet() : null, a.getNomLibre(), a.nomAffiche(),
            a.getNationalite(), a.getNumeroPasseport(), a.getFonctionMission(), a.getCivilite());
    }
}

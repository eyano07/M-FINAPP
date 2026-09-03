package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.OrdreMission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrdreMissionResponse(
    Long id, String numero, String lieuMission, BigDecimal distanceVille, String province, String territoire,
    Long superviseurEmployeId, String superviseurNomComplet, String superviseurCivilite,
    String butMission, String dureeMission, LocalDate dateDepart, LocalDate dateRetour, String moyenTransport,
    String fraisMission, boolean collective, List<AgentOrdreMissionResponse> agents, Instant createdAt
) {
    public static OrdreMissionResponse from(OrdreMission o) {
        return new OrdreMissionResponse(
            o.getId(), o.getNumero(), o.getLieuMission(), o.getDistanceVille(), o.getProvince(), o.getTerritoire(),
            o.getSuperviseur() != null ? o.getSuperviseur().getId() : null,
            o.getSuperviseur() != null ? o.getSuperviseur().getNomComplet() : null, o.getSuperviseurCivilite(),
            o.getButMission(), o.getDureeMission(), o.getDateDepart(), o.getDateRetour(), o.getMoyenTransport(),
            o.getFraisMission(), o.isCollective(), o.getAgents().stream().map(AgentOrdreMissionResponse::from).toList(),
            o.getCreatedAt());
    }
}

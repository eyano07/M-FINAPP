package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.Size;

/**
 * Un agent est soit un employé enregistré ({@code employeId}), soit une
 * personne externe identifiée par {@code nomLibre} (partenaire, entrepreneur
 * étranger...) — exactement l'un des deux, voir {@code OrdreMissionService.valider}.
 */
public record AgentOrdreMissionInput(
    Long employeId,
    @Size(max = 150) String nomLibre,
    @Size(max = 60) String nationalite,
    @Size(max = 60) String numeroPasseport,
    @Size(max = 150) String fonctionMission,
    @Size(max = 10) String civilite
) {}

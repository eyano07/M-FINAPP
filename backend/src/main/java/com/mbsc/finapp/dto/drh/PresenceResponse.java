package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.Presence;
import com.mbsc.finapp.domain.enums.StatutPresence;

import java.time.LocalDate;

public record PresenceResponse(
    Long id, Long employeId, LocalDate date, StatutPresence statut, String motif
) {
    public static PresenceResponse from(Presence p) {
        return new PresenceResponse(p.getId(), p.getEmploye().getId(), p.getDate(), p.getStatut(), p.getMotif());
    }
}

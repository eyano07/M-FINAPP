package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.enums.PrioriteNote;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Definition de la priorite de paiement par le DA, accompagnee d'une
 * observation facultative. Applicable a une note validee (VALIDEE_DA).
 */
public record PrioriteRequest(

    @NotNull
    PrioriteNote priorite,

    @Size(max = 1000)
    String commentaire
) {}

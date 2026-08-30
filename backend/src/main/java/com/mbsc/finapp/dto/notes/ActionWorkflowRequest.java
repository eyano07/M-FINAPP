package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.Size;

/**
 * Commentaire facultatif accompagnant une action de workflow
 * (verification, validation, rejet, transmission...).
 * Le rejet exige obligatoirement un motif : la contrainte est verifiee
 * dans le service.
 */
public record ActionWorkflowRequest(

    @Size(max = 1000)
    String commentaire
) {}

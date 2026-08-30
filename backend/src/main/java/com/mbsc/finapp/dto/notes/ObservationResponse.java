package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.time.Instant;

/**
 * Une entree de la chronologie (timeline) du workflow d'une note.
 */
public record ObservationResponse(
    Long id,
    String auteurNom,
    StatutNote statutAuMoment,
    String commentaire,
    Instant dateAction
) {
    public static ObservationResponse from(ObservationNote o) {
        var auteur = o.getAuteur();
        String nom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        return new ObservationResponse(
            o.getId(),
            nom,
            o.getStatutAuMoment(),
            o.getCommentaire(),
            o.getDateAction()
        );
    }
}

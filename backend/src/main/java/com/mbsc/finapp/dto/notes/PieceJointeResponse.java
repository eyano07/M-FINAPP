package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.PieceJointe;

import java.time.Instant;

public record PieceJointeResponse(
    Long id,
    String nomFichier,
    String typeMime,
    Long taille,
    String ajoutePar,
    Instant dateAjout
) {
    public static PieceJointeResponse from(PieceJointe pj) {
        var auteur = pj.getAjoutePar();
        String nomAuteur = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        return new PieceJointeResponse(
            pj.getId(),
            pj.getNomFichier(),
            pj.getTypeMime(),
            pj.getTaille(),
            nomAuteur,
            pj.getDateAjout()
        );
    }
}

package com.mbsc.finapp.dto.instance;

import java.time.Instant;

/**
 * Action faite sur la copie en ligne, telle qu'affichee a l'ecran (en attente, appliquee ou refusee par le serveur local).
 *
 * @param resultatNoteId note creee par le serveur local (nouvelle note), une fois appliquee
 */
public record ActionDistanteResponse(
    String uuid,
    String type,
    String libelle,
    Long noteId,
    String resume,
    String statut,
    String message,
    Long resultatNoteId,
    String auteur,
    Instant creeLe,
    Instant traiteLe
) {}

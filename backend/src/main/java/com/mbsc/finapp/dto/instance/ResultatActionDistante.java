package com.mbsc.finapp.dto.instance;

/** Resultat de l'application d'une action par le serveur local, renvoye au serveur en ligne. */
public record ResultatActionDistante(String uuid, String statut, String message, Long resultatNoteId) {}

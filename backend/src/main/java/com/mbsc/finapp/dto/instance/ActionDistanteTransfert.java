package com.mbsc.finapp.dto.instance;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Action en attente transmise par le serveur en ligne au serveur local (appel signe), avec la description de ses pieces
 * jointes (a telecharger par numero).
 */
public record ActionDistanteTransfert(
    String uuid,
    String type,
    Long noteId,
    Long cibleId,
    JsonNode contenu,
    List<Fichier> fichiers,
    Long auteurId,
    String auteurIdentifiant,
    String statutNoteVu,
    Instant dateMajNoteVue,
    String resume,
    Instant creeLe
) {
    /** Piece jointe d'une nouvelle note, gardee sur le serveur en ligne jusqu'a son application. */
    public record Fichier(int numero, String nom, String typeMime, long taille) {}
}

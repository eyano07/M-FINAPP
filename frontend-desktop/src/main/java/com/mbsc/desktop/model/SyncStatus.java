package com.mbsc.desktop.model;

/** Etat d'une entree dans la file de synchronisation (Outbox). */
public enum SyncStatus {
    PENDING,   // en attente d'envoi
    SENT,      // accepte par le serveur
    FAILED,    // echec temporaire (sera reessaye)
    CONFLICT   // conflit a resoudre manuellement
}
